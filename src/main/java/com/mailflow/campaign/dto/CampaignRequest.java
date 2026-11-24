package com.mailflow.campaign.dto;

import com.mailflow.common.util.EmailContentRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Body of create (POST) and update (PUT) of a campaign.
 *
 * <p>On update the scalar fields use PUT semantics: each is replaced, so a
 * {@code null} optional value clears it. {@code templateId} {@code null} keeps the
 * current template.
 *
 * @param subject           what recipients see, after personalisation: required, at most
 *                          998 characters, one line without control characters
 *                          ({@link EmailContentRules})
 * @param recipientGroupIds on update, {@code null} keeps the stored groups and an
 *                          empty list removes them all
 * @param recipientIds      on update, {@code null} keeps the stored contacts and an
 *                          empty list removes them all
 * @param targetAddresses campaign-only addresses (not saved as contacts), at most
 *                        10,000. On update, {@code null} keeps the stored addresses
 *                        and an empty list removes them all.
 * @param customBody      the campaign's own plain-text email body, sent instead of the
 *                        template's bodies; at most 100,000 characters, no control
 *                        characters except tab and line breaks. Raw HTML is not
 *                        interpreted: it is sent as text. On create, {@code null} or a
 *                        blank value means none (the template's body is used). On
 *                        update, {@code null} keeps the stored body, {@code ""} (or any
 *                        blank value) clears it, and any other value replaces it.
 */
public record CampaignRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotBlank
    @Size(max = EmailContentRules.SUBJECT_MAX_LENGTH, message = EmailContentRules.SUBJECT_TOO_LONG)
    @Pattern(regexp = EmailContentRules.SUBJECT_PATTERN, message = EmailContentRules.SUBJECT_NOT_ONE_LINE)
    String subject,
    Long templateId,
    String senderName,
    @Email String senderEmail,
    @Email String replyTo,
    Instant scheduledAt,
    List<Long> recipientGroupIds,
    List<Long> recipientIds,
    @Valid @Size(max = 10_000) List<TargetAddress> targetAddresses,
    @Size(max = EmailContentRules.BODY_MAX_LENGTH, message = EmailContentRules.BODY_TOO_LONG)
    @Pattern(regexp = EmailContentRules.BODY_PATTERN, message = EmailContentRules.BODY_CONTROL_CHARACTERS)
    String customBody
) {}
