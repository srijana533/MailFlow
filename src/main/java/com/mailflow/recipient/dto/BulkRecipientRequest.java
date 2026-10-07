package com.mailflow.recipient.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Request to create / reuse a batch of recipients from previewed Excel rows.
 *
 * <p>Each entry carries the standard fields plus any extra columns in
 * {@code fields} (role, phone, location, interest, …), which are stored in
 * the recipient's {@code customFields}. Existing recipients (same email) are
 * reused, not duplicated.
 */
public record BulkRecipientRequest(
    @NotEmpty List<Row> rows
) {
    public record Row(
        String email,
        String name,
        String firstName,
        String lastName,
        String company,
        Map<String, String> fields
    ) {}
}
