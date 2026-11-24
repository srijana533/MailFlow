package com.mailflow.campaign.controller;

import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Campaigns", description = "Campaign lifecycle management")
public class CampaignController {

    /** OpenAPI description of PUT /api/campaigns/{id} (Markdown, shown in Swagger UI). */
    private static final String UPDATE_DESCRIPTION = """
            Edits a DRAFT campaign. Other statuses return 409.

            **Scalar fields use PUT semantics.** The request replaces `name`, `description`, \
            `subject`, `senderName`, `senderEmail`, `replyTo` and `scheduledAt`. An omitted or \
            `null` optional field is cleared, so send the current value of every field you want \
            to keep. `name` and `subject` are required. Saving never changes the status, so a \
            `scheduledAt` on a draft does not schedule it.

            **These fields are never cleared by omission:**
            - `templateId`: omitted or `null` keeps the current template. Another ID replaces it \
            (404 if that template doesn't exist or is deleted). A template can't be removed here.
            - `recipientIds`, `recipientGroupIds` and `targetAddresses`: omitted or `null` keeps \
            the stored value, `[]` clears it, and a list replaces it.
            - `customBody` (the campaign's own plain-text email body): omitted or `null` keeps \
            the stored body, `""` (or any blank value) clears it so the template's body is used, \
            and any other value replaces it.

            **Content.** `subject`: at most 998 characters, one line, no control characters. \
            `customBody`: at most 100,000 characters, no control characters except tab and line \
            breaks; it is plain text (HTML in it is sent as text, never interpreted). When a \
            campaign has a custom body, it is sent instead of the template's bodies: as the \
            text/plain part, plus a text/html part made from the rendered text with every value \
            HTML-escaped. Placeholders such as `{{FirstName}}` work in both the subject and the body.
            """;

    /** OpenAPI description of POST /api/campaigns (Markdown, shown in Swagger UI). */
    private static final String CREATE_DESCRIPTION = """
            Creates a DRAFT campaign (SCHEDULED when `scheduledAt` is set). A draft can be saved \
            without a template, a body or recipients; launch requires them.

            `customBody` is the campaign's own plain-text email body, sent instead of the \
            template's bodies; `null` or blank means none. Limits: `subject` at most 998 \
            characters on one line without control characters; `customBody` at most 100,000 \
            characters, no control characters except tab and line breaks.

            Responses of create, get and update include `customBody` (when set) and every \
            campaign-only address; the list includes neither, only `targetAddressCount` and \
            `hasContent`.
            """;

    /** OpenAPI description of POST /api/campaigns/{id}/launch (Markdown, shown in Swagger UI). */
    private static final String LAUNCH_DESCRIPTION = """
            Validates the campaign and queues it for sending. 400 when something is missing: \
            email content ("Campaign has no email content: choose a template or write the \
            email", which also covers a deleted template or one without a body), the subject, \
            the sender email, or recipients ("Campaign has no recipients selected"; never all \
            contacts), or when none of the selected recipients can be emailed. 409 when the \
            status doesn't allow a launch.
            """;

    /** OpenAPI description of DELETE /api/campaigns/{id} (Markdown, shown in Swagger UI). */
    private static final String DELETE_DESCRIPTION = """
            Soft-deletes a campaign: 204 on success. ADMIN or CAMPAIGN_MANAGER only.

            **Effect.** The campaign disappears from the list, search, the dashboard and \
            analytics; GET, analytics, edit, launch, pause, resume and cancel then answer 404, \
            and the scheduler never launches it. Its delivery history is kept: delivery rows \
            (with the address each email was sent to), email logs and unsubscribe entries. Its \
            campaign-only address list is deleted. Contacts, groups, templates and other \
            campaigns are not changed.

            **When it is allowed.** DRAFT, SCHEDULED, PAUSED, COMPLETED, FAILED and CANCELLED \
            campaigns, once no batch job of the campaign is running and none of its emails is \
            still waiting in the send queue. Otherwise 409 with the reason. QUEUED and RUNNING \
            always give 409: cancel the campaign, wait until sending stops, then delete it. \
            Emails queued more than 24 hours ago, and a job without progress for 24 hours, no \
            longer block deletion.

            404 if the campaign doesn't exist or is already deleted; 409 if it was launched or \
            changed at the same moment.
            """;

    private final CampaignService campaignService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create a new campaign", description = CREATE_DESCRIPTION)
    public ResponseEntity<CampaignResponse> create(@Valid @RequestBody CampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(request));
    }

    @GetMapping
    @Operation(summary = "List campaigns with optional filtering")
    public ResponseEntity<PageResponse<CampaignResponse>> list(
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        return ResponseEntity.ok(
            campaignService.findAll(status, search,
                PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(dir), sort))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get campaign details")
    public ResponseEntity<CampaignResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Update a DRAFT campaign", description = UPDATE_DESCRIPTION)
    public ResponseEntity<CampaignResponse> update(
            @PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Delete a campaign (soft delete)", description = DELETE_DESCRIPTION)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        campaignService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle actions ──────────────────────────────────────────────────

    @PostMapping("/{id}/launch")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Launch a campaign (validates and queues for processing)",
               description = LAUNCH_DESCRIPTION)
    public ResponseEntity<CampaignResponse> launch(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.launch(id));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Pause a running campaign")
    public ResponseEntity<CampaignResponse> pause(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.pause(id));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Resume a paused campaign")
    public ResponseEntity<CampaignResponse> resume(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.resume(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Cancel a campaign")
    public ResponseEntity<CampaignResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.cancel(id));
    }
}
