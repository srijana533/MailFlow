package com.mailflow.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmtpEmailProvider} From-address resolution.
 *
 * <p>No real SMTP connection is made: {@link JavaMailSender} is mocked and the
 * built {@link MimeMessage} is captured and inspected. {@code mailSender.send}
 * is a no-op mock, so no email is transmitted.
 */
@ExtendWith(MockitoExtension.class)
class SmtpEmailProviderTest {

    @Mock private JavaMailSender mailSender;

    private MimeMessage newMimeMessage() {
        // A detached MimeMessage backed by an empty session — never sent.
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private EmailRequest sampleRequest() {
        return EmailRequest.builder()
                .to("recipient@example.com")
                .from("campaign-sender@mycompany.com")
                .fromName("My Company")
                .subject("Hello")
                .htmlBody("<p>Hi</p>")
                .build();
    }

    @Test
    void gmail_mode_forces_from_to_authenticated_username() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        // Authenticated account configured (Gmail-style).
        SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, "me@gmail.com");
        provider.send(sampleRequest());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        // From must be the authenticated Gmail account (display name preserved).
        String from = sent.getFrom()[0].toString();
        assertThat(from).contains("me@gmail.com");
        assertThat(from).contains("My Company");

        // The original campaign sender is preserved as Reply-To.
        assertThat(sent.getReplyTo()[0].toString()).contains("campaign-sender@mycompany.com");
    }

    @Test
    void gmail_mode_keeps_explicit_reply_to() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        EmailRequest req = EmailRequest.builder()
                .to("recipient@example.com")
                .from("campaign-sender@mycompany.com")
                .fromName("My Company")
                .replyTo("support@mycompany.com")
                .subject("Hello")
                .htmlBody("<p>Hi</p>")
                .build();

        SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, "me@gmail.com");
        provider.send(req);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getFrom()[0].toString()).contains("me@gmail.com");
        // An explicit reply-to wins over the fallback.
        assertThat(sent.getReplyTo()[0].toString()).contains("support@mycompany.com");
    }

    @Test
    void mailhog_mode_keeps_campaign_sender_as_from() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        // No authenticated username (MailHog / un-authenticated relay).
        SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, "");
        provider.send(sampleRequest());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        // From is the campaign's own sender, unchanged.
        assertThat(sent.getFrom()[0].toString()).contains("campaign-sender@mycompany.com");
        // No reply-to override was added: Jakarta Mail's getReplyTo() falls back
        // to the From address when none was explicitly set, so it must equal From
        // (i.e. still the campaign sender, not any injected override).
        assertThat(sent.getReplyTo()[0].toString()).contains("campaign-sender@mycompany.com");
    }

    @Test
    void null_username_is_treated_as_unauthenticated() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, null);
        provider.send(sampleRequest());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()[0].toString())
                .contains("campaign-sender@mycompany.com");
    }
}
