package com.mailflow.recipient.dto;

import java.util.Map;

/**
 * A single parsed row from an uploaded file, with its validation outcome.
 *
 * <p>{@code status} is one of VALID, INVALID, DUPLICATE.
 * For INVALID/DUPLICATE rows, {@code reason} explains why.
 * {@code fields} holds all mapped values (email, name, company, role, …)
 * so the frontend can render a preview table and later submit the chosen rows.
 */
public record ExcelRowPreview(
    int rowNumber,
    String email,
    String name,
    String company,
    String status,          // VALID | INVALID | DUPLICATE
    String reason,          // null when VALID
    Map<String, String> fields
) {
    public static final String VALID = "VALID";
    public static final String INVALID = "INVALID";
    public static final String DUPLICATE = "DUPLICATE";
}
