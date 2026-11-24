package com.mailflow.recipient.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Request to create / reuse a batch of recipients from previewed Excel rows
 * or manually entered addresses.
 *
 * <p>Each entry carries the standard fields plus any extra columns in
 * {@code fields} (role, phone, location, interest, …), which are stored in
 * the recipient's {@code customFields}. Existing recipients (same email) are
 * reused, not duplicated.
 *
 * @param source optional origin of the rows: {@code "UPLOAD"} (default) or
 *               {@code "MANUAL"}. Recorded as the new contacts' import source so
 *               contacts created from a campaign form can be identified later.
 */
public record BulkRecipientRequest(
    @NotEmpty @Size(max = 10_000) List<Row> rows,
    String source
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
