package com.mailflow.batch;

import com.mailflow.analytics.EmailLogService;
import com.mailflow.analytics.entity.EmailLog;
import com.mailflow.analytics.repository.EmailLogRepository;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.email.EmailProvider;
import com.mailflow.email.EmailRequest;
import com.mailflow.email.RateLimiterService;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.email.entity.UnsubscribeEntry;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.queue.EmailConsumer;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateEngineService;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Null-safety of the send pipeline for campaign-only addresses: delivery rows and
 * queue messages without a saved contact (recipient / recipientId null).
 */
@ExtendWith(MockitoExtension.class)
class CampaignOnlyAddressPipelineTest {

    private static final String ADA_KEY = CampaignRecipient.buildAddressIdempotencyKey(7L, "ada@test.local");

    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private CampaignRepository runningCampaign;

    private CampaignItemProcessor processor;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        StringTemplateLoader loader = new StringTemplateLoader();
        freemarker.template.Configuration config =
                new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_32);
        config.setTemplateLoader(loader);
        config.setDefaultEncoding("UTF-8");
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        processor = new CampaignItemProcessor(
                new TemplateEngineService(config, loader), unsubscribeService, campaignRecipientRepository,
                runningCampaign);
        // The processor only renders while the campaign is RUNNING (not every test processes a row).
        lenient().when(runningCampaign.findStatusById(7L))
                .thenReturn(Optional.of(com.mailflow.campaign.entity.CampaignStatus.RUNNING));

        campaign = Campaign.builder()
                .id(7L)
                .subject("Hi {{FirstName}} ({{Team}})")
                .senderName("MailFlow")
                .senderEmail("noreply@mailflow.local")
                .replyTo("replies@test.local")
                .template(EmailTemplate.builder()
                        .htmlBody("<p>{{Name}} at {{Company}}, {{Email}}, role: {{Role}}</p>")
                        .plainBody("Dear {{name}} {{LastName}}")
                        .build())
                .build();
    }

    private CampaignRecipient adaRow() {
        return CampaignRecipient.builder()
                .id(100L)
                .campaign(campaign)
                .idempotencyKey(ADA_KEY)
                .deliveryStatus(DeliveryStatus.PENDING)
                .snapshotEmail("ada@test.local")
                .snapshotName("Ada Lovelace")
                .snapshotFirstName("Ada")
                .snapshotLastName("Lovelace")
                .snapshotCompany("Acme")
                .snapshotCustomFields(Map.of("Team", "Platform"))
                .build();
    }

    private static EmailMessage messageWithoutContact() {
        return EmailMessage.builder()
                .idempotencyKey(ADA_KEY)
                .campaignId(7L)
                .recipientId(null)
                .toAddress("ada@test.local")
                .senderEmail("noreply@mailflow.local")
                .subject("Hi Ada")
                .htmlBody("<p>Hi Ada</p>")
                .plainBody("Hi Ada")
                .attemptNumber(1)
                .createdAt(Instant.now())
                .build();
    }

    // ── Processor ───────────────────────────────────────────────────────────────

    @Test
    void processor_personalises_from_the_snapshot_and_sends_to_the_snapshot_address() {
        CampaignRecipient row = adaRow();

        EmailMessage msg = processor.process(row);

        assertThat(msg).isNotNull();
        assertThat(msg.toAddress()).isEqualTo("ada@test.local");
        assertThat(msg.recipientId()).isNull();
        assertThat(msg.idempotencyKey()).isEqualTo(ADA_KEY);
        assertThat(msg.campaignId()).isEqualTo(7L);
        assertThat(msg.replyTo()).isEqualTo("replies@test.local");
        assertThat(msg.subject()).isEqualTo("Hi Ada (Platform)");
        assertThat(msg.htmlBody()).isEqualTo("<p>Ada Lovelace at Acme, ada@test.local, role: </p>");
        assertThat(msg.plainBody()).isEqualTo("Dear Ada Lovelace Lovelace");
        // The transient Recipient used for the model is never attached to the row.
        assertThat(row.getRecipient()).isNull();
        verify(unsubscribeService).isUnsubscribed("ada@test.local");
        verify(campaignRecipientRepository, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void processor_skips_an_unsubscribed_campaign_only_address() {
        when(unsubscribeService.isUnsubscribed("ada@test.local")).thenReturn(true);

        assertThat(processor.process(adaRow())).isNull();
        verify(campaignRecipientRepository).markFailed(
                eq(ADA_KEY), eq(DeliveryStatus.SKIPPED), any(Instant.class), eq("Unsubscribed"));
    }

    @Test
    void processor_marks_a_row_without_any_address_skipped_instead_of_failing() {
        CampaignRecipient broken = CampaignRecipient.builder()
                .id(101L).campaign(campaign).idempotencyKey("campaign_7_addr_broken")
                .deliveryStatus(DeliveryStatus.PENDING).build();

        assertThat(processor.process(broken)).isNull();
        // Marked so the row leaves the reader's PENDING/FAILED result set.
        verify(campaignRecipientRepository).markFailed(
                eq("campaign_7_addr_broken"), eq(DeliveryStatus.SKIPPED), any(Instant.class), eq("No email address"));
        verifyNoInteractions(unsubscribeService);
    }

    @Test
    void skip_listener_handles_a_row_without_a_contact() {
        CampaignRecipient row = adaRow();

        assertThatCode(() -> new CampaignSkipListener()
                .onSkipInProcess(row, new IllegalArgumentException("bad template")))
                .doesNotThrowAnyException();
        assertThat(row.targetEmail()).isEqualTo("ada@test.local");
    }

    // ── Consumer, delivery log, unsubscribe ─────────────────────────────────────

    @Test
    void consumer_sends_a_message_without_recipient_id_and_records_it() {
        EmailProvider emailProvider = org.mockito.Mockito.mock(EmailProvider.class);
        CampaignRepository campaignRepository = org.mockito.Mockito.mock(CampaignRepository.class);
        RateLimiterService rateLimiter = org.mockito.Mockito.mock(RateLimiterService.class);
        EmailLogService emailLogService = org.mockito.Mockito.mock(EmailLogService.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = org.mockito.Mockito.mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.hasKey("email:sent:" + ADA_KEY)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(rateLimiter.tryAcquire(7L)).thenReturn(true);
        // The consumer re-checks the campaign status before sending.
        when(campaignRepository.findStatusById(7L))
                .thenReturn(Optional.of(com.mailflow.campaign.entity.CampaignStatus.RUNNING));
        EmailMessage message = messageWithoutContact();

        new EmailConsumer(emailProvider, campaignRecipientRepository, campaignRepository,
                rateLimiter, emailLogService, redisTemplate).consume(message);

        ArgumentCaptor<EmailRequest> sent = ArgumentCaptor.forClass(EmailRequest.class);
        verify(emailProvider).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("ada@test.local");
        verify(campaignRecipientRepository).markSent(eq(ADA_KEY), any(Instant.class));
        verify(campaignRepository).incrementCounters(eq(7L), eq(1L), eq(0L), eq(0L), eq(0L), any(Instant.class));
        verify(emailLogService).logSuccess(message);
    }

    @Test
    void delivery_log_accepts_a_message_without_recipient_id() {
        EmailLogRepository emailLogRepository = org.mockito.Mockito.mock(EmailLogRepository.class);

        new EmailLogService(emailLogRepository).logSuccess(messageWithoutContact());

        ArgumentCaptor<EmailLog> saved = ArgumentCaptor.forClass(EmailLog.class);
        verify(emailLogRepository).save(saved.capture());
        assertThat(saved.getValue().getRecipientId()).isNull();
        assertThat(saved.getValue().getToAddress()).isEqualTo("ada@test.local");
        assertThat(saved.getValue().getCampaignId()).isEqualTo(7L);
        assertThat(saved.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void unsubscribing_a_campaign_only_address_adds_it_to_the_unsubscribe_list() {
        UnsubscribeRepository unsubscribeRepository = org.mockito.Mockito.mock(UnsubscribeRepository.class);
        RecipientRepository recipientRepository = org.mockito.Mockito.mock(RecipientRepository.class);
        // The address is normalised before it is looked up and stored, so a later send to
        // "ada@test.local" finds the entry.
        when(unsubscribeRepository.existsByEmailIgnoreCase("ada@test.local")).thenReturn(false);
        when(recipientRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("ada@test.local")).thenReturn(Optional.empty());

        String token = new UnsubscribeService(unsubscribeRepository, recipientRepository)
                .unsubscribe(" Ada@Test.Local\u00A0", "No thanks", 7L);

        ArgumentCaptor<UnsubscribeEntry> entry = ArgumentCaptor.forClass(UnsubscribeEntry.class);
        verify(unsubscribeRepository).save(entry.capture());
        assertThat(entry.getValue().getEmail()).isEqualTo("ada@test.local");
        assertThat(entry.getValue().getCampaignId()).isEqualTo(7L);
        assertThat(entry.getValue().getToken()).isEqualTo(token).isNotBlank();
        // No saved contact exists for the address, so none is changed or created.
        verify(recipientRepository, never()).save(any());
        verify(recipientRepository, never()).findById(anyLong());
    }
}
