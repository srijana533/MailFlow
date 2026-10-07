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

        if (!ai.isEnabled() || ai.getOpenai().getApiKey().isBlank()) {
            log.info("AI email copilot: DISABLED (set AI_ENABLED=true and OPENAI_API_KEY to enable)");
            return new NoOpAiContentProvider();
        }

        log.info("AI email copilot: ENABLED (provider={})", ai.getProvider());
        return new OpenAiContentProvider(properties, webClientBuilder, objectMapper);
    }
}
