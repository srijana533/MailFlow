package com.mailflow.template.controller;

import com.mailflow.common.dto.PageResponse;
import com.mailflow.template.dto.TemplateContentRenderRequest;
import com.mailflow.template.dto.TemplatePreviewRequest;
import com.mailflow.template.dto.TemplateRenderRequest;
import com.mailflow.template.dto.TemplateRenderResponse;
import com.mailflow.template.dto.TemplateRequest;
import com.mailflow.template.dto.TemplateResponse;
import com.mailflow.template.service.TemplateService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Templates", description = "Email template management")
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create an email template")
    public ResponseEntity<TemplateResponse> create(@Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @GetMapping
    @Operation(summary = "List templates")
    public ResponseEntity<PageResponse<TemplateResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            templateService.findAll(search, PageRequest.of(page, size, Sort.by("createdAt").descending())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a template by ID")
    public ResponseEntity<TemplateResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Update a template")
    public ResponseEntity<TemplateResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(templateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Delete a template")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/preview")
    @Operation(summary = "Preview a rendered template with sample variables")
    public ResponseEntity<Map<String, String>> preview(
            @PathVariable Long id,
            @RequestBody TemplatePreviewRequest request) {
        return ResponseEntity.ok(templateService.preview(id, request.variables()));
    }

    @PostMapping("/{id}/render")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Render a template for one recipient (personalised preview)",
               description = "Uses the same personalisation model as a real campaign send. "
                           + "The recipient is supplied inline and nothing is saved.")
    public ResponseEntity<TemplateRenderResponse> renderForRecipient(
            @PathVariable Long id,
            @Valid @RequestBody TemplateRenderRequest request) {
        return ResponseEntity.ok(templateService.renderForRecipient(id, request));
    }

    @PostMapping("/render")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Render unsaved template content for a sample recipient",
               description = "Preview while a template is created or edited. Renders the supplied subject, "
                           + "HTML body and plain-text body with the same personalisation model and template "
                           + "engine as a real campaign send, and returns the same shape as "
                           + "POST /api/templates/{id}/render. Nothing is saved. Limits: subject 1,000 "
                           + "characters; each body 500,000 characters; at most 50 custom fields, with keys "
                           + "up to 40 and values up to 1,000 characters.")
    public ResponseEntity<TemplateRenderResponse> renderContent(
            @Valid @RequestBody TemplateContentRenderRequest request) {
        return ResponseEntity.ok(templateService.renderContent(request));
    }
}
