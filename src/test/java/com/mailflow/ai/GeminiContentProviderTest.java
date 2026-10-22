package com.mailflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.common.exception.MailFlowException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.config.MailFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GeminiContentProvider} using an in-memory stubbed
 * WebClient exchange — NO real network call and NO real Gemini API key is used.
 */
class GeminiContentProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MailFlowProperties props() {
        MailFlowProperties p = new MailFlowProperties();
        p.setAi(new MailFlowProperties.Ai());
        p.getAi().setEnabled(true);
        p.getAi().setProvider("gemini");
        p.getAi().getGemini().setApiKey("test-key-not-real");
        p.getAi().getGemini().setModel("gemini-1.5-flash");
        p.getAi().getGemini().setTimeoutSeconds(5);
        return p;
    }

    /** Builds a provider whose WebClient returns the given canned response. */
    private GeminiContentProvider providerReturning(ClientResponse response) {
        ExchangeFunction exchange = request -> Mono.just(response);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new GeminiContentProvider(props(), builder, mapper);
    }

    private GeminiContentProvider providerThrowing(Throwable t) {
        ExchangeFunction exchange = request -> Mono.error(t);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new GeminiContentProvider(props(), builder, mapper);
    }

    private ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static final String GEMINI_ENVELOPE = """
            {"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}
            """;

    @Test
    void improve_returns_subject_and_body_from_gemini() {
        // The candidate text is itself a JSON string (escaped) per responseMimeType=json.
        String innerJson = "{\\\"subject\\\":\\\"Improved Subject\\\",\\\"body\\\":\\\"Improved body with {{Name}}\\\"}";
        String envelope = String.format(GEMINI_ENVELOPE, innerJson);

        GeminiContentProvider provider = providerReturning(okJson(envelope));

        ImprovedEmail result = provider.improve(new ImproveEmailRequest(
                "Original subject", "Original body with {{Name}}",
                "promote workshop", "students", "professional", "date and venue"));

        assertThat(result.subject()).isEqualTo("Improved Subject");
        assertThat(result.body()).contains("Improved body").contains("{{Name}}");
    }

    @Test
    void improve_rejects_blank_subject_or_body() {
        GeminiContentProvider provider = providerReturning(okJson("{}"));
        assertThatThrownBy(() -> provider.improve(
                new ImproveEmailRequest("", "body", null, null, null, null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> provider.improve(
                new ImproveEmailRequest("subject", "  ", null, null, null, null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void improve_falls_back_to_original_when_model_omits_fields() {
        // Candidate returns an empty JSON object → keep originals.
        String envelope = String.format(GEMINI_ENVELOPE, "{}");
        GeminiContentProvider provider = providerReturning(okJson(envelope));

        ImprovedEmail result = provider.improve(new ImproveEmailRequest(
                "Keep me", "Keep this body", null, null, null, null));

        assertThat(result.subject()).isEqualTo("Keep me");
        assertThat(result.body()).isEqualTo("Keep this body");
    }

    @Test
    void rate_limit_maps_to_service_unavailable() {
        WebClientResponseException e429 = WebClientResponseException.create(
                429, "Too Many Requests", null, null, null);
        GeminiContentProvider provider = providerThrowing(e429);

        assertThatThrownBy(() -> provider.improve(
                new ImproveEmailRequest("s", "b", null, null, null, null)))
                .isInstanceOf(MailFlowException.class)
                .satisfies(ex -> assertThat(((MailFlowException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void auth_error_maps_to_service_unavailable() {
        WebClientResponseException e403 = WebClientResponseException.create(
                403, "Forbidden", null, null, null);
        GeminiContentProvider provider = providerThrowing(e403);

        assertThatThrownBy(() -> provider.improve(
                new ImproveEmailRequest("s", "b", null, null, null, null)))
                .isInstanceOf(MailFlowException.class)
                .satisfies(ex -> assertThat(((MailFlowException) ex).getErrorCode())
                        .isEqualTo("AI_AUTH_ERROR"));
    }

    @Test
    void malformed_envelope_maps_to_bad_gateway() {
        GeminiContentProvider provider = providerReturning(okJson("{\"unexpected\":true}"));

        assertThatThrownBy(() -> provider.improve(
                new ImproveEmailRequest("s", "b", null, null, null, null)))
                .isInstanceOf(MailFlowException.class)
                .satisfies(ex -> assertThat(((MailFlowException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void is_available_true_for_configured_gemini() {
        GeminiContentProvider provider = providerReturning(okJson("{}"));
        assertThat(provider.isAvailable()).isTrue();
    }
}
