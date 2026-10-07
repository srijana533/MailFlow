package com.mailflow.ai;

import java.util.List;

/**
 * Content returned by an AI provider after email generation.
 */
public record AiGeneratedContent(
    String subject,
    String htmlBody,
    String plainBody,
    List<String> suggestedVariables
) {}
