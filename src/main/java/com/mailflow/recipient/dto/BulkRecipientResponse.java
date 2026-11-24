package com.mailflow.recipient.dto;

import java.util.List;

/**
 * Result of a bulk recipient create/reuse operation.
 * {@code recipientIds} are the IDs (new or existing) to associate with a campaign.
 * {@code skippedDeleted} counts addresses that belong to deleted contacts; they are
 * neither re-created nor reactivated.
 */
public record BulkRecipientResponse(
    int created,
    int reused,
    int skippedInvalid,
    int skippedDeleted,
    List<Long> recipientIds
) {}
