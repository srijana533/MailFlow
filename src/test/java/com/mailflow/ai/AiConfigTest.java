package com.mailflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.common.exception.MailFlowException;
import com.mailflow.config.MailFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests provider selection in {@link AiConfig} and the disabled-AI fallback.
 * No network calls are made.
 */
class AiConfigTest {

    private final WebClient.Builder webClient = WebClient.builder();
    private final ObjectMapper mapper = new ObjectMapper();

    private AiContentProvider build(MailFlowProperties p) {
        AiConfig cfg = new AiConfig(p);
        return cfg.aiContentProvider(webClient, mapper);
    }

    private MailFlowProperties base() {
        MailFlowProperties p = new MailFlowProperties();
        p.setAi(new MailFlowProperties.Ai());
        return p;
    }

    @Test
    void disabled_returns_noop_and_reports_unavailable() {
        MailFlowProperties p = base();
        p.getAi().setEnabled(false);
        AiContentProvider provider = build(p);
        assertThat(provider).isInstanceOf(NoOpAiContentProvider.class);
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void gemini_selected_when_enabled_with_key() {
        MailFlowProperties p = base();
        p.getAi().setEnabled(true);
        p.getAi().setProvider("gemini");
        p.getAi().getGemini().setApiKey("a-key");
        assertThat(build(p)).isInstanceOf(GeminiContentProvider.class);
    }

    @Test
    void gemini_without_key_falls_back_to_noop() {
        MailFlowProperties p = base();
        p.getAi().setEnabled(true);
        p.getAi().setProvider("gemini");
        p.getAi().getGemini().setApiKey("");
        assertThat(build(p)).isInstanceOf(NoOpAiContentProvider.class);
    }

    @Test
    void openai_selected_when_enabled_with_key() {
        MailFlowProperties p = base();
        p.getAi().setEnabled(true);
        p.getAi().setProvider("openai");
        p.getAi().getOpenai().setApiKey("a-key");
        assertThat(build(p)).isInstanceOf(OpenAiContentProvider.class);
    }

    @Test
    void unknown_provider_falls_back_to_noop() {
        MailFlowProperties p = base();
        p.getAi().setEnabled(true);
        p.getAi().setProvider("sillicon-brain");
        assertThat(build(p)).isInstanceOf(NoOpAiContentProvider.class);
    }

    @Test
    void noop_improve_throws_service_unavailable() {
        NoOpAiContentProvider noop = new NoOpAiContentProvider();
        assertThatThrownBy(() -> noop.improve(
                new ImproveEmailRequest("s", "b", null, null, null, null)))
                .isInstanceOf(MailFlowException.class)
                .satisfies(ex -> assertThat(((MailFlowException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
