package com.mailflow.analytics.dto;

/**
 * Analytics snapshot for a single campaign.
 */
public record CampaignAnalytics(
    Long campaignId,
    String campaignName,
    long total,
    long sent,
    long failed,
    long skipped,
    long bounced,
    long retried,
    double successRate,
    double throughputPerMinute,
    Long durationSeconds
) {}
