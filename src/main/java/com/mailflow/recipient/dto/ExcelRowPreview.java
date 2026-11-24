package com.mailflow.recipient.dto;

import java.util.Map;

/**
 * A single parsed row from an uploaded file, with its validation outcome.
 *
 * <p>{@code status} is one of:
 * <ul>
 *   <li>{@code VALID}: a new address that is not a saved contact yet.</li>
 *   <li>{@code EXISTING}: the address belongs to a saved contact that can be emailed.
 *       The saved contact (and its stored details) is used; no new contact is created.
 *       {@code recipientId} is that contact's ID.</li>
 *   <li>{@code SKIPPED}: a valid address that won't be emailed, because it is on the
 *       unsubscribe list or its saved contact is unsubscribed, bounced or marked
 *       invalid. {@code recipientId} is set when it belongs to a saved contact.</li>
 *   <li>{@code DUPLICATE}: the address already appeared earlier in the same file.</li>
 *   <li>{@code INVALID}: missing or malformed address, no email column, or an
 *       address that belongs to a deleted contact.</li>
 * </ul>
 * For every status except VALID, {@code reason} explains the outcome.
 * {@code email} (and {@code fields.email}) is the normalised address when it is
 * valid, otherwise the value found in the file.
 * {@code fields} holds all mapped values (email, name, company, role, …)
 * so the frontend can render a preview table and later submit the chosen rows.
 */
public record ExcelRowPreview(
    int rowNumber,
    String email,
    String name,
    String company,
    String status,          // VALID | EXISTING | SKIPPED | DUPLICATE | INVALID
    String reason,          // null when VALID
    Map<String, String> fields,
    Long recipientId        // saved contact ID when EXISTING (or SKIPPED for a contact), otherwise null
) {
    public static final String VALID = "VALID";
    public static final String EXISTING = "EXISTING";
    public static final String SKIPPED = "SKIPPED";
    public static final String INVALID = "INVALID";
    public static final String DUPLICATE = "DUPLICATE";
}
