package com.mailflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.common.exception.MailFlowException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.config.MailFlowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini (Generative Language API) integration for AI-assisted email
 * drafting. Uses the {@code models/{model}:generateContent} endpoint with the
 * API key supplied via the {@code GEMINI_API_KEY} environment variable
 * (never hardcoded).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Model is asked to return strict JSON ({@code responseMimeType}).</li>
 *   <li>Timeouts are bounded; provider/rate-limit errors map to clean HTTP
 *       errors for the caller.</li>
 *   <li>Email bodies and the API key are never logged.</li>
 * </ul>
 */
@Slf4j
public class GeminiContentProvider implements AiContentProvider {

    private final MailFlowProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public GeminiContentProvider(MailFlowProperties properties,
                                 WebClient.Builder webClientBuilder,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGeneratedContent generate(String prompt) {
        // Reuse the improve pipeline shape for a free-form prompt: treat the
        // prompt as the body with no subject, then adapt the result.
        if (prompt == null || prompt.isBlank()) {
            throw new ValidationException("Prompt must not be empty");
        }
        String json = callGemini(AiPromptBuilder.IMPROVE_SYSTEM_PROMPT,
                "Draft a professional marketing email for: " + prompt
                        + "\nReturn JSON {\"subject\":\"...\",\"body\":\"...\"}");
        JsonNode parsed = extractJsonObject(json);
        String subject = parsed.path("subject").asText("");
        String body = parsed.path("body").asText("");
        return new AiGeneratedContent(subject, body, body, List.of());
    }

    @Override
    public ImprovedEmail improve(ImproveEmailRequest request) {
        if (request == null || request.subject() == null || request.body() == null
                || request.subject().isBlank() || request.body().isBlank()) {
            throw new ValidationException("Both subject and body are required");
        }

        String userPrompt = AiPromptBuilder.buildImprovePrompt(request);
        String json = callGemini(AiPromptBuilder.IMPROVE_SYSTEM_PROMPT, userPrompt);
        JsonNode parsed = extractJsonObject(json);

        // Fall back to the original values if the model omits a field.
        String subject = parsed.path("subject").asText(request.subject());
        String body = parsed.path("body").asText(request.body());
        if (subject.isBlank()) subject = request.subject();
        if (body.isBlank()) body = request.body();
        return new ImprovedEmail(subject, body);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Calls the Gemini generateContent endpoint and returns the raw response
     * body. The API key is passed as a query param per Google's API; it is
     * never logged.
     */
    private String callGemini(String systemInstruction, String userPrompt) {
        MailFlowProperties.Ai.Gemini cfg = properties.getAi().getGemini();

        String uri = String.format("%s/models/%s:generateContent?key=%s",
                cfg.getBaseUrl(), cfg.getModel(), cfg.getApiKey());

        Map<String, Object> requestBody = Map.of(
            "systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemInstruction))
            ),
            "contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userPrompt))
            )),
            "generationConfig", Map.of(
                "temperature", 0.5,
                "responseMimeType", "application/json"
            )
        );

        try {
            return webClientBuilder.build()
                    .post()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();
        } catch (WebClientResponseException e) {
            int code = e.getRawStatusCode();
            // Do not log the response body (may echo prompt content).
            log.warn("Gemini API error: HTTP {}", code);
            if (code == 429) {
                throw new MailFlowException(HttpStatus.SERVICE_UNAVAILABLE, "AI_RATE_LIMITED",
                    "AI service is rate-limited. Please try again in a moment.");
            }
            if (code == 400 || code == 401 || code == 403) {
                throw new MailFlowException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AUTH_ERROR",
                    "AI service rejected the request. Check the configured API key and model.");
            }
            throw new MailFlowException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_ERROR",
                "AI provider returned an error. Please try again later.");
        } catch (MailFlowException e) {
            throw e;
        } catch (Exception e) {
            // Timeouts, connection resets, etc.
            log.warn("Gemini call failed: {}", e.getClass().getSimpleName());
            throw new MailFlowException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                "AI service did not respond in time. Please try again.");
        }
    }

    /**
     * Extracts the model's JSON object from the Gemini response envelope. Gemini
     * returns {@code candidates[0].content.parts[0].text}, which (with
     * responseMimeType=application/json) is itself a JSON string.
     */
    private JsonNode extractJsonObject(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new IllegalStateException("no candidates in response");
            }
            String text = candidates.get(0)
                    .path("content").path("parts").get(0).path("text").asText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("empty candidate text");
            }
            return objectMapper.readTree(text);
        } catch (MailFlowException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response: {}", e.getClass().getSimpleName());
            throw new MailFlowException(HttpStatus.BAD_GATEWAY, "AI_BAD_RESPONSE",
                "AI service returned an unexpected response. Please try again.");
        }
    }
}
