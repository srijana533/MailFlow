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

    private final CampaignService campaignService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create a new campaign")
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
    @Operation(summary = "Update a DRAFT campaign")
    public ResponseEntity<CampaignResponse> update(
            @PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Delete a campaign")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        campaignService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle actions ──────────────────────────────────────────────────

    @PostMapping("/{id}/launch")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Launch a campaign (validates and queues for processing)")
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
