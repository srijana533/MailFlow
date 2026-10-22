package com.mailflow.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to improve/polish an existing email draft with AI (Option A:
 * AI-assisted drafting with human review).
 *
 * <p>Only {@code subject} and {@code body} are required. The optional context
 * fields steer the rewrite. The AI must preserve the user's intended meaning,
 * factual details, links and calls to action — it must not invent recipient
 * details, offers, or business facts.
 */
public record ImproveEmailRequest(

    @NotBlank(message = "subject is required")
    @Size(max = 500, message = "subject must be at most 500 characters")
    String subject,

    @NotBlank(message = "body is required")
    @Size(max = 20_000, message = "body must be at most 20000 characters")
    String body,

    /** Optional: what the campaign is trying to achieve. */
    @Size(max = 1_000) String purpose,

    /** Optional: who the email is for. */
    @Size(max = 1_000) String audience,

    /** Optional tone: professional | friendly | persuasive | concise. */
    @Size(max = 50) String tone,

    /** Optional: key points that must be retained. */
    @Size(max = 2_000) String keyPoints
) {}
