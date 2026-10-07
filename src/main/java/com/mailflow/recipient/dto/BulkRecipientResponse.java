package com.mailflow.recipient.dto;

import java.util.List;

/**
 * Result of a bulk recipient create/reuse operation.
 * {@code recipientIds} are the IDs (new or existing) to associate with a campaign.
 */
public record BulkRecipientResponse(
    int created,
    int reused,
    int skippedInvalid,
    List<Long> recipientIds
) {}
