package com.mailflow.email;

import com.mailflow.queue.PermanentEmailException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SMTP email delivery using Spring's {@link JavaMailSender}.
 *
 * <p>Distinguishes permanent from temporary failures:
 * <ul>
 *   <li>Permanent (5xx): wraps in {@link PermanentEmailException}</li>
 *   <li>Temporary (4xx, connection timeout): rethrows as-is for retry</li>
 * </ul>
 *
 * <p><b>From-address compatibility:</b> authenticated providers such as Gmail
 * reject (or silently rewrite) a From header that does not match the
 * authenticated account. When an authenticated SMTP username is configured
 * ({@code authenticatedUsername} non-blank — i.e. Gmail / any SMTP_AUTH=true
 * setup) the envelope From is forced to that username so the message is
 * accepted, while the campaign's intended sender is preserved as Reply-To.
 * For MailHog / un-authenticated SMTP the username is blank, so the campaign's
 * own sender address is used unchanged (existing behaviour).
 */
@Slf4j
public class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;

    /**
     * The authenticated SMTP account (spring.mail.username / SMTP_USERNAME).
     * Blank for MailHog / un-authenticated SMTP.
     */
    private final String authenticatedUsername;

    public SmtpEmailProvider(JavaMailSender mailSender, String authenticatedUsername) {
        this.mailSender = mailSender;
        this.authenticatedUsername = authenticatedUsername == null ? "" : authenticatedUsername.trim();
    }

    @Override
    public void send(EmailRequest request) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(request.to());

            // Resolve the envelope From. With an authenticated account the From
            // must be the authenticated user (Gmail requirement); otherwise use
            // the campaign's configured sender as-is.
            String envelopeFrom = resolveFromAddress(request.from());
            helper.setFrom(buildAddress(envelopeFrom, request.fromName()));

            // Preserve the campaign's intended sender for replies. If the caller
            // set an explicit reply-to it wins; otherwise, when we overrode the
            // From for authentication, fall back to the original sender so
            // recipients still reply to the right place.
            String replyTo = request.replyTo();
            if ((replyTo == null || replyTo.isBlank())
                    && isAuthenticated()
                    && request.from() != null && !request.from().isBlank()
                    && !request.from().equalsIgnoreCase(authenticatedUsername)) {
                replyTo = request.from();
            }
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }

            if (request.cc() != null && !request.cc().isEmpty()) {
                helper.setCc(request.cc().toArray(new String[0]));
            }
            if (request.bcc() != null && !request.bcc().isEmpty()) {
                helper.setBcc(request.bcc().toArray(new String[0]));
            }

            helper.setSubject(request.subject());

            if (request.htmlBody() != null && !request.htmlBody().isBlank()) {
                if (request.plainBody() != null && !request.plainBody().isBlank()) {
                    helper.setText(request.plainBody(), request.htmlBody());
                } else {
                    helper.setText(request.htmlBody(), true);
                }
            } else if (request.plainBody() != null) {
                helper.setText(request.plainBody(), false);
            }

            // Attachments
            if (request.attachments() != null) {
                for (EmailRequest.AttachmentRef att : request.attachments()) {
                    helper.addAttachment(att.filename(),
                        new org.springframework.core.io.FileSystemResource(att.path()));
                }
            }

            mailSender.send(message);
            log.debug("SMTP: sent to {}", request.to());

        } catch (MailSendException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            // 5xx codes indicate permanent failure
            if (msg.contains("550") || msg.contains("551") || msg.contains("553")
                    || msg.contains("554") || msg.contains("user unknown")) {
                throw new PermanentEmailException("Permanent SMTP rejection for " + request.to(), e);
            }
            throw e; // Retryable
        } catch (jakarta.mail.MessagingException e) {
            throw new org.springframework.mail.MailPreparationException(
                "Failed to build message for " + request.to(), e);
        }
    }

    /** True when an authenticated SMTP account is configured (e.g. Gmail). */
    private boolean isAuthenticated() {
        return !authenticatedUsername.isEmpty();
    }

    /**
     * Picks the envelope From address. For an authenticated account the From
     * is forced to the authenticated username (Gmail compatibility); otherwise
     * the request's own from address is used. Falls back to the request from
     * if, for any reason, no authenticated username is set.
     */
    private String resolveFromAddress(String requestedFrom) {
        if (isAuthenticated()) {
            return authenticatedUsername;
        }
        return requestedFrom;
    }

    private String buildAddress(String email, String name) {
        if (name != null && !name.isBlank()) {
            try {
                return new jakarta.mail.internet.InternetAddress(email, name).toString();
            } catch (Exception e) {
                return email;
            }
        }
        return email;
    }
}
