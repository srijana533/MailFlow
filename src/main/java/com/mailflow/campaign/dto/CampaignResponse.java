package com.mailflow.campaign.dto;

import com.mailflow.campaign.entity.CampaignStatus;

import java.time.Instant;
import java.util.List;

public record CampaignResponse(
    Long id,
    String name,
    String description,
    String subject,
    Long templateId,
    String templateName,
    String senderName,
    String senderEmail,
    CampaignStatus status,
    Instant scheduledAt,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    long totalRecipients,
    long sentCount,
    long failedCount,
    long skippedCount,
    long bouncedCount,
    long retryCount,
    double successRate,
    String batchJobId,
    List<Long> recipientGroupIds,
    List<Long> recipientIds
) {}
