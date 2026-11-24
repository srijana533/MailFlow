package com.mailflow.recipient.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ImportResult;
import com.mailflow.recipient.dto.RecipientLookupRequest;
import com.mailflow.recipient.dto.RecipientLookupResponse;
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
import java.util.Map;

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
        Sort order = Sort.by(direction, sort);
        // Many contacts share created_at (rows inserted in one transaction, e.g. the 100
        // seed contacts), and PostgreSQL returns ties in any order, so pages could
        // repeat or skip contacts. The id makes the order, and so the pages, stable.
        if (!"id".equals(sort)) {
            order = order.and(Sort.by(direction, "id"));
        }
        PageRequest pageable = PageRequest.of(page, size, order);
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
               description = "Accepts .xlsx, .xls or .csv (max 5 MB, 10,000 rows). Parses the file, "
                           + "maps headers and returns a per-row preview. Nothing is persisted.\n\n"
                           + "Row statuses: VALID (new address), EXISTING (a saved contact, reused with its "
                           + "recipientId), SKIPPED (unsubscribed, or a saved contact that bounced or is marked "
                           + "invalid: never emailed), DUPLICATE (repeats an earlier row of the same file, which "
                           + "the reason names) and INVALID (missing or malformed address, or a deleted "
                           + "contact). Each non-VALID row has a reason. Counts: validRows = newRows + "
                           + "existingRows (everything that can be emailed); totalRows = validRows + "
                           + "invalidRows + duplicateRows + skippedRows. Earlier campaigns are never "
                           + "consulted: a saved contact can be in any number of campaigns.\n\n"
                           + "Addresses are trimmed (including invisible characters such as U+00A0, U+200B "
                           + "and U+FEFF) and lower-cased before they are checked. Cells such as "
                           + "'Ada Lovelace <ada@example.com>' and 'mailto:ada@example.com' are accepted; the "
                           + "display name is not used (map a Name column for names).\n\n"
                           + "The optional 'mapping' part is a JSON object of header -> field overrides, where a "
                           + "field is email, name, firstName, lastName, company, custom:<key> or ignore.")
    public ResponseEntity<ExcelPreviewResponse> previewUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping) {
        return ResponseEntity.ok(importService.previewUpload(file, parseMapping(mapping)));
    }

    @PostMapping(value = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Find saved contacts by ID and/or email (read-only)",
               description = "Returns non-deleted contacts matching the IDs or emails (any status), the "
                           + "requested emails that belong to deleted contacts, and the requested emails "
                           + "on the unsubscribe list. Emails are trimmed and lower-cased first. "
                           + "Nothing is created.")
    public ResponseEntity<RecipientLookupResponse> lookup(
            @Valid @RequestBody RecipientLookupRequest request) {
        return ResponseEntity.ok(recipientService.lookup(request));
    }

    /** Parses the optional JSON column mapping sent with an upload preview. */
    private static Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPING_READER.readValue(json);
        } catch (IOException e) {
            throw new ValidationException(
                    "The column mapping must be a JSON object of column name to field.");
        }
    }

    private static final ObjectReader MAPPING_READER =
            new ObjectMapper().readerFor(new TypeReference<Map<String, String>>() {});

    @PostMapping(value = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create or reuse recipients from previewed rows",
               description = "Creates new recipients and reuses existing ones (by email, trimmed and "
                           + "lower-cased), returning the recipient IDs to attach to a campaign. An address "
                           + "that is already a saved contact never gets a second contact.")
    public ResponseEntity<BulkRecipientResponse> bulkCreate(
            @Valid @RequestBody BulkRecipientRequest request) {
        return ResponseEntity.ok(importService.bulkCreateOrReuse(request));
    }

    @GetMapping("/template/excel")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Download a sample .xlsx import template",
               description = "Columns Email, Name and Company (the same columns as the campaign form's "
                           + "\"Add emails manually\" table) with three example rows at example.com. "
                           + "Uploads also accept other columns, which can be mapped to first name, "
                           + "last name or custom fields.")
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
