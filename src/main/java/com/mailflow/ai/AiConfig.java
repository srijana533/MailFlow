package com.mailflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Selects the active AI provider based on configuration.
 *
 * <p>If {@code mailflow.ai.enabled=false} or no API key is set,
 * the {@link NoOpAiContentProvider} is used and AI endpoints return
 * a clear "not configured" error without breaking anything else.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final MailFlowProperties properties;

    @Bean
    public AiContentProvider aiContentProvider(WebClient.Builder webClientBuilder,
                                                ObjectMapper objectMapper) {
        MailFlowProperties.Ai ai = properties.getAi();

        if (!ai.isEnabled()) {
            log.info("AI assistance: DISABLED (set AI_ENABLED=true to enable)");
            return new NoOpAiContentProvider();
        }

        String provider = ai.getProvider() == null ? "" : ai.getProvider().trim().toLowerCase();

        if ("gemini".equals(provider)) {
            if (ai.getGemini().getApiKey().isBlank()) {
                log.warn("AI assistance: provider=gemini but GEMINI_API_KEY is not set — AI disabled");
                return new NoOpAiContentProvider();
            }
            log.info("AI assistance: ENABLED (provider=gemini, model={})", ai.getGemini().getModel());
            return new GeminiContentProvider(properties, webClientBuilder, objectMapper);
        }

        if ("openai".equals(provider)) {
            if (ai.getOpenai().getApiKey().isBlank()) {
                log.warn("AI assistance: provider=openai but OPENAI_API_KEY is not set — AI disabled");
                return new NoOpAiContentProvider();
            }
            log.info("AI assistance: ENABLED (provider=openai, model={})", ai.getOpenai().getModel());
            return new OpenAiContentProvider(properties, webClientBuilder, objectMapper);
        }

        log.warn("AI assistance: unknown provider '{}' — AI disabled", provider);
        return new NoOpAiContentProvider();
    }
}
