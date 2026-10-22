package com.mailflow.ai;

import com.mailflow.common.exception.MailFlowException;
import org.springframework.http.HttpStatus;

/**
 * Fallback AI provider used when AI is disabled or no API key is configured.
 * The rest of the application works normally without AI — only the AI
 * endpoints return a clear "not configured" error (HTTP 503).
 */
public class NoOpAiContentProvider implements AiContentProvider {

    private static final String MESSAGE =
        "AI assistance is not configured. Set AI_ENABLED=true and provide an API key "
        + "(GEMINI_API_KEY for provider=gemini) to enable this feature.";

    @Override
    public AiGeneratedContent generate(String prompt) {
        throw new MailFlowException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", MESSAGE);
    }

    @Override
    public ImprovedEmail improve(ImproveEmailRequest request) {
        throw new MailFlowException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", MESSAGE);
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
