package com.mailflow.campaign.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * A campaign-only address: emailed by this campaign without being saved as a contact.
 *
 * <p>In requests the email is validated, trimmed and lower-cased by the service
 * (an invalid address is rejected with a 400 that names it). The other limits match
 * the database columns. {@code fields} holds extra personalisation values such as
 * {@code {"Team": "Platform"}}; keys equal to a standard field (email, name,
 * firstName, lastName, company) are ignored.
 */
public record TargetAddress(
    String email,
    @Size(max = 255) String name,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 255) String company,
    @Size(max = 50) Map<@Size(max = 40) String, @Size(max = 1000) String> fields
) {}
