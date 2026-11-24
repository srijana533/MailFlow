package com.mailflow.recipient.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing (but NOT persisting) an uploaded Excel/CSV file.
 *
 * <p>Returned by the preview endpoint so the frontend can show a validation
 * summary and a per-row preview before the user commits to importing /
 * sending. No recipients are created during preview.
 *
 * <p>Counts: {@code totalRows = validRows + invalidRows + duplicateRows + skippedRows},
 * and {@code validRows = newRows + existingRows}. Whether an address was emailed by
 * another campaign plays no part: a saved contact can be in any number of campaigns.
 */
public record ExcelPreviewResponse(
    String fileName,
    int totalRows,
    /** Rows that can be emailed: new addresses plus saved contacts (newRows + existingRows). */
    int validRows,
    /** Valid rows whose address is not a saved contact yet. */
    int newRows,
    /** Valid rows whose address belongs to a saved contact (reused, never re-created). */
    int existingRows,
    /** Rows with a missing or malformed address, or an address of a deleted contact. */
    int invalidRows,
    /** Repeats of an address that appeared earlier in the same file. */
    int duplicateRows,
    /** Valid addresses that won't be emailed: unsubscribed, or a saved contact that bounced or is marked invalid. */
    int skippedRows,
    /** All non-empty header names, in file order. */
    List<String> columns,
    /** Header name -> target field after auto-detection and any requested overrides. */
    Map<String, String> detectedColumns,
    /** Header names that are not used (not recognised, or set to "ignore"). */
    List<String> unmappedColumns,
    /** Whether an email column was detected at all. */
    boolean hasEmailColumn,
    /** Per-row preview (all rows, capped by the caller if needed). */
    List<ExcelRowPreview> rows
) {}
