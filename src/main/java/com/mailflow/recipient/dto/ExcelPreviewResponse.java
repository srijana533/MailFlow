package com.mailflow.recipient.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing (but NOT persisting) an uploaded Excel/CSV file.
 *
 * <p>Returned by the preview endpoint so the frontend can show a validation
 * summary and a per-row preview before the user commits to importing /
 * sending. No recipients are created during preview.
 */
public record ExcelPreviewResponse(
    String fileName,
    int totalRows,
    int validRows,
    int invalidRows,
    int duplicateRows,
    /** Recognised header name -> target field (for the column-mapping UI). */
    Map<String, String> detectedColumns,
    /** Header names that were present but not recognised. */
    List<String> unmappedColumns,
    /** Whether an email column was detected at all. */
    boolean hasEmailColumn,
    /** Per-row preview (all rows, capped by the caller if needed). */
    List<ExcelRowPreview> rows
) {}
