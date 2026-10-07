package com.mailflow.analytics.dto;

public record DashboardSummary(
    long totalCampaigns,
    long activeCampaigns,
    long totalEmailsSent,
    long totalEmailsFailed,
    double overallSuccessRate,
    long totalRecipients
) {}
