package com.mailflow.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Strongly-typed configuration properties for MailFlow.
 * All values are resolved from application.yml / environment variables.
 * No secrets are stored in source code.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mailflow")
public class MailFlowProperties {

    @NotNull
    private Security security = new Security();

    @NotNull
    private Email email = new Email();

    @NotNull
    private Batch batch = new Batch();

    @NotNull
    private Storage storage = new Storage();

    @NotNull
    private Ai ai = new Ai();

    @NotNull
    private Unsubscribe unsubscribe = new Unsubscribe();

    // ── Nested classes ────────────────────────────────────────────────────

    @Data
    public static class Security {
        @NotNull
        private Jwt jwt = new Jwt();

        @Data
        public static class Jwt {
            @NotBlank
            private String secret;
            private long accessTokenExpiryMs = 3_600_000L;
            private long refreshTokenExpiryMs = 604_800_000L;
        }
    }

    @Data
    public static class Email {
        private String fromName = "MailFlow";
        private String fromAddress = "noreply@mailflow.local";

        @Min(1)
        private int rateLimitPerMinute = 100;

        /** "development" uses fake SMTP; "production" uses real SMTP */
        private String mode = "development";
    }

    @Data
    public static class Batch {
        @Min(1)
        private int chunkSize = 50;

        @Min(0)
        private int maxRetries = 3;

        @Min(0)
        private int skipLimit = 100;

        @Min(1)
        private int throttleLimit = 4;
    }

    @Data
    public static class Storage {
        private String uploadDir = "./uploads";
        private int maxAttachmentSizeMb = 10;
        private List<String> allowedMimeTypes = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png",
            "image/jpeg",
            "text/plain",
            "application/zip"
        );
    }

    @Data
    public static class Ai {
        private boolean enabled = false;
        private String provider = "openai";

        @NotNull
        private OpenAi openai = new OpenAi();

        @Data
        public static class OpenAi {
            private String apiKey = "";
            private String model = "gpt-4o-mini";
            private String baseUrl = "https://api.openai.com/v1";
            private int timeoutSeconds = 30;
        }
    }

    @Data
    public static class Unsubscribe {
        private String baseUrl = "http://localhost:8080";
        private int tokenExpiryDays = 365;
    }
}
