package com.mailflow.email;

import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Selects the active {@link EmailProvider} based on the configured mode.
 *
 * <p>Set {@code mailflow.email.mode=development} (default) for console output.
 * Set {@code mailflow.email.mode=production} to use real SMTP.
 *
 * <p>The real SMTP provider is handed the authenticated SMTP username
 * (from {@code spring.mail.username} / {@code SMTP_USERNAME}) so it can force a
 * Gmail-compatible From address. The username is blank for MailHog, in which
 * case the campaign's own sender address is used unchanged.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmailProviderConfig {

    private final MailFlowProperties properties;
    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    @Bean
    public EmailProvider emailProvider() {
        String mode = properties.getEmail().getMode();

        if ("production".equalsIgnoreCase(mode)) {
            String username = mailProperties.getUsername();
            boolean authenticated = username != null && !username.isBlank();
            log.info("Email provider: SMTP (production mode) host={} port={} auth={}",
                    mailProperties.getHost(), mailProperties.getPort(), authenticated);
            return new SmtpEmailProvider(mailSender, username);
        }

        log.warn("Email provider: DEV MODE — emails will be logged to console, not sent");
        return new DevEmailProvider();
    }
}
