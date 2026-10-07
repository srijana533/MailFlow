package com.mailflow.recipient.controller;

import com.mailflow.common.dto.PageResponse;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ImportResult;
import com.mailflow.recipient.dto.RecipientRequest;
import com.mailflow.recipient.dto.RecipientResponse;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.service.RecipientImportService;
import com.mailflow.recipient.service.RecipientService;
import com.opencsv.exceptions.CsvValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/recipients")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Recipients", description = "Recipient management and bulk import")
public class RecipientController {

    private final RecipientService recipientService;
    private final RecipientImportService importService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create a single recipient")
    public ResponseEntity<RecipientResponse> create(@Valid @RequestBody RecipientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recipientService.create(request));
    }

    @GetMapping
    @Operation(summary = "List recipients with search and pagination")
    public ResponseEntity<PageResponse<RecipientResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RecipientStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir) {

        Sort.Direction direction = Sort.Direction.fromString(dir);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, sort));
        return ResponseEntity.ok(recipientService.findAll(search, status, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a recipient by ID")
    public ResponseEntity<RecipientResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(recipientService.findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Update a recipient")
    public ResponseEntity<RecipientResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody RecipientRequest request) {
        return ResponseEntity.ok(recipientService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Soft-delete a recipient")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        recipientService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Import ───────────────────────────────────────────────────────────────

    @PostMapping(value = "/import/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Bulk import recipients from a CSV file",
               description = "Expected headers: email,name,firstName,lastName,company,course,code")
    public ResponseEntity<ImportResult> importCsv(@RequestParam("file") MultipartFile file)
            throws IOException, CsvValidationException {
        return ResponseEntity.ok(importService.importCsv(file));
    }

    @PostMapping(value = "/import/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Bulk import recipients from an Excel (.xlsx) file")
    public ResponseEntity<ImportResult> importExcel(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ResponseEntity.ok(importService.importExcel(file));
    }

    // ── Bulk-from-Excel: preview, create/reuse, template ────────────────────────

    @PostMapping(value = "/import/excel/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Validate an Excel/CSV upload WITHOUT saving",
               description = "Parses the file, maps headers, flags invalid/duplicate rows, "
                           + "and returns a per-row preview. Nothing is persisted.")
    public ResponseEntity<ExcelPreviewResponse> previewUpload(
            @RequestParam("file") MultipartFile file)
            throws IOException, CsvValidationException {
        String fn = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase();
        ExcelPreviewResponse preview = fn.endsWith(".csv")
                ? importService.previewCsv(file)
                : importService.previewExcel(file);
        return ResponseEntity.ok(preview);
    }

    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create or reuse recipients from previewed rows",
               description = "Creates new recipients and reuses existing ones (by email), "
                           + "returning the recipient IDs to attach to a campaign.")
    public ResponseEntity<BulkRecipientResponse> bulkCreate(
            @Valid @RequestBody BulkRecipientRequest request) {
        return ResponseEntity.ok(importService.bulkCreateOrReuse(request));
    }

    @GetMapping("/template/excel")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Download a sample .xlsx import template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        byte[] body = importService.buildExcelTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"mailflow-recipients-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
