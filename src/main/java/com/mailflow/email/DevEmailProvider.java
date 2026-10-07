package com.mailflow.email;

import lombok.extern.slf4j.Slf4j;

/**
 * Development-mode email provider.
 *
 * <p>Logs the email content to the console instead of sending it.
 * Active when {@code mailflow.email.mode=development}.
 *
 * <p>This prevents accidental SMTP sends during local development
 * and removes the need for MailHog/real SMTP in basic test environments.
 */
@Slf4j
public class DevEmailProvider implements EmailProvider {

    @Override
    public void send(EmailRequest request) {
        log.info("""
                ╔══════════════════════════════════════════════════════════
                ║  [DEV MODE] Email not actually sent
                ║  To:      {}
                ║  From:    {} <{}>
                ║  Subject: {}
                ║  HTML body length: {} chars
                ╚══════════════════════════════════════════════════════════
                """,
                request.to(),
                request.fromName(), request.from(),
                request.subject(),
                request.htmlBody() != null ? request.htmlBody().length() : 0
        );
    }

    @Override
    public String providerName() {
        return "DevEmailProvider (console)";
    }
}
