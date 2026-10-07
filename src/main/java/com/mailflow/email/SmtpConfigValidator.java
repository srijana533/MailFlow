package com.mailflow.email;

import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the SMTP configuration at application startup so that an
 * incomplete authenticated-SMTP setup (e.g. Gmail selected but credentials
 * missing) fails fast with a clear message, rather than only surfacing later
 * when a campaign is launched and every email silently fails.
 *
 * <p>Validation only applies when:
 * <ul>
 *   <li>{@code mailflow.email.mode=production} (real SMTP is in use), and</li>
 *   <li>SMTP authentication is enabled ({@code SMTP_AUTH=true}).</li>
 * </ul>
 *
 * <p>For the MailHog / local-dev configuration ({@code SMTP_AUTH=false} or
 * development mode) no credentials are required and validation is skipped, so
 * existing local workflows are unaffected.
 *
 * <p>This performs configuration validation only — it does <b>not</b> open an
 * SMTP connection or send any email.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpConfigValidator implements InitializingBean {

    private final MailFlowProperties mailFlowProperties;
    private final MailProperties mailProperties;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Validates SMTP settings. Package-private so it can be unit-tested
     * directly without starting a Spring context.
     *
     * @throws IllegalStateException if production + SMTP auth is enabled but
     *         required credentials or host are missing.
     */
    void validate() {
        String mode = mailFlowProperties.getEmail().getMode();
        boolean production = "production".equalsIgnoreCase(mode);

        if (!production) {
            log.info("SMTP config check skipped (email mode='{}', not production).", mode);
            return;
        }

        boolean authEnabled = isAuthEnabled();
        if (!authEnabled) {
            // MailHog / un-authenticated relay — credentials not required.
            log.info("SMTP config check: production mode with authentication disabled "
                    + "(host={}, port={}). Treating as un-authenticated relay (e.g. MailHog).",
                    mailProperties.getHost(), mailProperties.getPort());
            return;
        }

        // Authenticated SMTP (e.g. Gmail): host + username + password are required.
        List<String> missing = new ArrayList<>();
        if (isBlank(mailProperties.getHost())) {
            missing.add("SMTP_HOST");
        }
        if (isBlank(mailProperties.getUsername())) {
            missing.add("SMTP_USERNAME");
        }
        if (isBlank(mailProperties.getPassword())) {
            missing.add("SMTP_PASSWORD");
        }

        if (!missing.isEmpty()) {
            String message = String.format(
                "Invalid SMTP configuration: email mode is 'production' and SMTP_AUTH=true, "
                + "but the following required environment variable(s) are missing or blank: %s. "
                + "For Gmail set SMTP_HOST=smtp.gmail.com, SMTP_PORT=587, SMTP_AUTH=true, "
                + "SMTP_TLS=true, SMTP_USERNAME=<gmail-address>, "
                + "SMTP_PASSWORD=<google-app-password>. "
                + "For local testing with MailHog set SMTP_AUTH=false (no credentials needed). "
                + "See docs/EMAIL_SMTP_SETUP.md.",
                String.join(", ", missing));
            throw new IllegalStateException(message);
        }

        log.info("SMTP config check passed: authenticated SMTP configured for host={} port={} user={}",
                mailProperties.getHost(), mailProperties.getPort(),
                maskUsername(mailProperties.getUsername()));
    }

    /**
     * Reads the effective {@code mail.smtp.auth} flag from Spring's
     * {@link MailProperties#getProperties()} map (populated from
     * {@code spring.mail.properties.mail.smtp.auth} / {@code SMTP_AUTH}).
     * Defaults to {@code false} when unset.
     */
    private boolean isAuthEnabled() {
        String auth = mailProperties.getProperties().get("mail.smtp.auth");
        return Boolean.parseBoolean(auth);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Masks the local part of an email for safe logging (never logs secrets). */
    private static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "(none)";
        }
        int at = username.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return username.charAt(0) + "***" + username.substring(at);
    }
}
