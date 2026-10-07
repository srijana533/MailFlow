package com.mailflow.ai;

import com.mailflow.common.exception.ValidationException;

/**
 * Fallback AI provider used when AI is disabled or the API key is not configured.
 * The rest of the application works normally without AI.
 */
public class NoOpAiContentProvider implements AiContentProvider {

    @Override
    public AiGeneratedContent generate(String prompt) {
        throw new ValidationException(
            "AI content generation is not configured. " +
            "Set AI_ENABLED=true and OPENAI_API_KEY to enable this feature.");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
