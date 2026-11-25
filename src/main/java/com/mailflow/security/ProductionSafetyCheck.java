package com.mailflow.security;

import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * With the {@code prod} profile, refuses to start on settings that would make a public
 * deployment unsafe, and names the variable to fix.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSafetyCheck implements InitializingBean {

    /** The fallback secret in application.yml; anyone can forge tokens with it. */
    static final String DEFAULT_SECRET = "change-me-in-production-must-be-at-least-256-bits-long-secret-key";

    private final MailFlowProperties properties;

    @Value("${FRONTEND_URL:}")
    private String frontendUrl;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        String secret = properties.getSecurity().getJwt().getSecret();
        if (secret == null || secret.length() < 32 || DEFAULT_SECRET.equals(secret)
                || secret.startsWith("your-")) {
            problems.add("JWT_SECRET must be set to a random value of at least 32 characters "
                    + "(for example the output of: openssl rand -base64 48)");
        }
        if (properties.getSecurity().isDemoAccountsEnabled()) {
            problems.add("DEMO_ACCOUNTS_ENABLED must be false (the demo passwords are public)");
        }
        if (frontendUrl == null || frontendUrl.isBlank()) {
            problems.add("FRONTEND_URL must be the deployed frontend address, e.g. https://your-app.vercel.app");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n  - " + String.join("\n  - ", problems));
        }
        log.info("Production safety check passed");
    }
}
