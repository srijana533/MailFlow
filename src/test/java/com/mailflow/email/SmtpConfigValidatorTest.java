package com.mailflow.email;

import com.mailflow.config.MailFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SmtpConfigValidator}. These validate configuration
 * only — no SMTP connection is opened and no email is sent.
 */
class SmtpConfigValidatorTest {

    private MailFlowProperties mailFlowProps(String mode) {
        MailFlowProperties p = new MailFlowProperties();
        p.setEmail(new MailFlowProperties.Email());
        p.getEmail().setMode(mode);
        return p;
    }

    private MailProperties mailProps(String host, Integer port, String user, String pass, Boolean auth) {
        MailProperties m = new MailProperties();
        m.setHost(host);
        if (port != null) m.setPort(port);
        m.setUsername(user);
        m.setPassword(pass);
        if (auth != null) m.getProperties().put("mail.smtp.auth", String.valueOf(auth));
        return m;
    }

    @Test
    void skips_validation_in_development_mode() {
        // Development mode: even with no credentials, must not throw.
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("development"),
                mailProps("localhost", 1025, "", "", false));
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void passes_mailhog_production_without_auth() {
        // Production + MailHog (auth disabled) needs no credentials.
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("production"),
                mailProps("localhost", 1025, "", "", false));
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void passes_gmail_production_with_full_credentials() {
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("production"),
                mailProps("smtp.gmail.com", 587, "sender@gmail.com", "app-password-xyz", true));
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void fails_when_production_auth_but_username_missing() {
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("production"),
                mailProps("smtp.gmail.com", 587, "", "app-password-xyz", true));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_USERNAME")
                .hasMessageContaining("production");
    }

    @Test
    void fails_when_production_auth_but_password_missing() {
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("production"),
                mailProps("smtp.gmail.com", 587, "sender@gmail.com", "", true));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_PASSWORD");
    }

    @Test
    void fails_lists_all_missing_vars() {
        SmtpConfigValidator v = new SmtpConfigValidator(
                mailFlowProps("production"),
                mailProps("", 587, "", "", true));
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).contains("SMTP_HOST");
                    assertThat(msg).contains("SMTP_USERNAME");
                    assertThat(msg).contains("SMTP_PASSWORD");
                });
    }
}
