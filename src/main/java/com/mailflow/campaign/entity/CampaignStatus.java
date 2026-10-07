package com.mailflow.campaign.entity;

/**
 * Full lifecycle of a campaign from creation to completion.
 *
 * <pre>
 * DRAFT → SCHEDULED → QUEUED → RUNNING → COMPLETED
 *              ↓                  ↓
 *          CANCELLED           PAUSED → RUNNING
 *                                ↓
 *                             FAILED
 * </pre>
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
