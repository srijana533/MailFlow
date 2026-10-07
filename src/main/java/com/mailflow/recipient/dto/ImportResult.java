package com.mailflow.recipient.dto;

import java.util.List;

/**
 * Summary result returned after a CSV/Excel import operation.
 */
public record ImportResult(
    int totalRows,
    int imported,
    int skippedDuplicates,
    int invalidRows,
    List<String> errors
) {
    public static ImportResult empty() {
        return new ImportResult(0, 0, 0, 0, List.of());
    }
}
