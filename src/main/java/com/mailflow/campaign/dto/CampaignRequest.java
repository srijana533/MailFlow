package com.mailflow.campaign.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record CampaignRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotBlank String subject,
    Long templateId,
    String senderName,
    @Email String senderEmail,
    String replyTo,
    Instant scheduledAt,
    List<Long> recipientGroupIds,
    List<Long> recipientIds
) {}
