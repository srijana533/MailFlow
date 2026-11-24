package com.mailflow.recipient.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Read-only lookup of saved contacts by ID and/or email address.
 *
 * <p>Used by the campaign form to reload a draft's selected recipients and to tell
 * which uploaded or typed addresses already belong to saved contacts. Nothing is
 * created or changed.
 */
public record RecipientLookupRequest(
    @Size(max = 10_000) List<Long> ids,
    @Size(max = 10_000) List<String> emails
) {}
