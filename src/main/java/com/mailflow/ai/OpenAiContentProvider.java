package com.mailflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat API integration for AI email generation.
 *
 * <p>The prompt instructs the model to return structured JSON so that
 * subject and body can be split cleanly. The user can edit the output
 * before using it in a campaign.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAiContentProvider implements AiContentProvider {

    private static final String SYSTEM_PROMPT = """
            You are a professional email copywriter for a B2B SaaS platform.
            When given a brief description, you generate a professional email.
            Always respond in JSON format with exactly these fields:
            {
              "subject": "...",
              "htmlBody": "...",
              "plainBody": "...",
              "suggestedVariables": ["name", "course", ...]
            }
            Use {{name}}, {{email}}, {{course}}, {{company}}, {{code}} as personalization variables where appropriate.
            Keep the tone professional and concise.
            """;

    private final MailFlowProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AiGeneratedContent generate(String prompt) {
        MailFlowProperties.Ai.OpenAi config = properties.getAi().getOpenai();

        String jsonResponse = webClientBuilder.build()
                .post()
                .uri(config.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                    "model", config.getModel(),
                    "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.7,
                    "response_format", Map.of("type", "json_object")
                ))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .block();

        return parseResponse(jsonResponse);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private AiGeneratedContent parseResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText();

            JsonNode parsed = objectMapper.readTree(content);
            List<String> vars = objectMapper.convertValue(
                parsed.path("suggestedVariables"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );

            return new AiGeneratedContent(
                parsed.path("subject").asText(),
                parsed.path("htmlBody").asText(),
                parsed.path("plainBody").asText(),
                vars
            );
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", e.getMessage());
            throw new RuntimeException("AI response parsing failed: " + e.getMessage(), e);
        }
    }
}
