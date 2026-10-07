package com.mailflow.email;

/**
 * Abstraction over email delivery providers.
 *
 * <p>Current implementations:
 * <ul>
 *   <li>{@link SmtpEmailProvider} — real SMTP via Jakarta Mail</li>
 *   <li>{@link DevEmailProvider}  — logs emails to console in development mode</li>
 * </ul>
 *
 * <p>Future providers (SendGrid, Amazon SES) can be added by implementing
 * this interface without changing any other code.
 */
public interface EmailProvider {

    /**
     * Sends an email.
     *
     * @param request the email to send
     * @throws com.mailflow.queue.PermanentEmailException for non-retryable failures
     * @throws org.springframework.mail.MailException for retryable failures
     */
    void send(EmailRequest request);

    /** Returns a human-readable identifier for logging. */
    default String providerName() { return getClass().getSimpleName(); }
}
