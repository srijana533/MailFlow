package com.mailflow.batch;

import com.mailflow.analytics.EmailLogService;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.config.RabbitMQConfig;
import com.mailflow.email.EmailProvider;
import com.mailflow.email.RateLimiterService;
import com.mailflow.queue.EmailConsumer;
import com.mailflow.queue.EmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pause, resume and cancel through the send pipeline: the job listener never
 * overwrites PAUSED or CANCELLED, the writer never publishes a row twice, and the
 * email consumer re-checks the campaign status before sending.
 *
 * <p>The JPQL behind the repository calls is run on H2 in
 * {@link CampaignPauseResumeQueriesTest}.
 */
@ExtendWith(MockitoExtension.class)
class CampaignPauseResumeTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final String KEY = "campaign_7_recipient_10";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;

    // ── Job listener ────────────────────────────────────────────────────────────

    private static JobExecution execution(BatchStatus status) {
        JobExecution e = new JobExecution(1L,
                new JobParametersBuilder().addLong("campaignId", CAMPAIGN_ID).toJobParameters());
        e.setStatus(status);
        return e;
    }

    @Test
    void job_start_marks_only_a_queued_campaign_running() {
        when(campaignRepository.updateStatusIfIn(eq(CAMPAIGN_ID), eq(CampaignStatus.RUNNING),
                eq(List.of(CampaignStatus.QUEUED)), any(Instant.class))).thenReturn(0);

        // 0 rows: for example cancelled while queued. Nothing else is written.
        new CampaignJobListener(campaignRepository).beforeJob(execution(BatchStatus.STARTED));

        verify(campaignRepository).updateStatusIfIn(eq(CAMPAIGN_ID), eq(CampaignStatus.RUNNING),
                eq(List.of(CampaignStatus.QUEUED)), any(Instant.class));
        verifyNoMoreInteractions(campaignRepository);
    }

    @Test
    void job_end_changes_only_a_running_campaign_so_paused_and_cancelled_are_kept() {
        CampaignJobListener listener = new CampaignJobListener(campaignRepository);

        listener.afterJob(execution(BatchStatus.COMPLETED));
        listener.afterJob(execution(BatchStatus.FAILED));
        listener.afterJob(execution(BatchStatus.STOPPED));

        verify(campaignRepository).updateStatusIfIn(eq(CAMPAIGN_ID), eq(CampaignStatus.COMPLETED),
                eq(List.of(CampaignStatus.RUNNING)), any(Instant.class));
        verify(campaignRepository).updateStatusIfIn(eq(CAMPAIGN_ID), eq(CampaignStatus.FAILED),
                eq(List.of(CampaignStatus.RUNNING)), any(Instant.class));
        verify(campaignRepository).updateStatusIfIn(eq(CAMPAIGN_ID), eq(CampaignStatus.PAUSED),
                eq(List.of(CampaignStatus.RUNNING)), any(Instant.class));
        // The unconditional update that used to overwrite PAUSED with COMPLETED is gone.
        verify(campaignRepository, never()).updateStatus(anyLong(), any(), any());
        verifyNoMoreInteractions(campaignRepository);
    }

    // ── Writer ──────────────────────────────────────────────────────────────────

    @Mock private RabbitTemplate rabbitTemplate;

    private static EmailMessage message(String key) {
        return EmailMessage.builder()
                .idempotencyKey(key)
                .campaignId(CAMPAIGN_ID)
                .recipientId(10L)
                .toAddress("ada@test.local")
                .senderEmail("noreply@mailflow.local")
                .subject("Hi Ada")
                .htmlBody("<p>Hi Ada</p>")
                .plainBody("Hi Ada")
                .attemptNumber(1)
                .createdAt(Instant.now())
                .build();
    }

    private CampaignItemWriter writerOfARunningCampaign() {
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(CampaignStatus.RUNNING));
        return new CampaignItemWriter(rabbitTemplate, campaignRecipientRepository, campaignRepository);
    }

    @Test
    void writer_marks_a_row_queued_before_publishing_it() {
        EmailMessage m = message(KEY);
        when(campaignRecipientRepository.markQueued(eq(KEY), any(Instant.class))).thenReturn(1);

        writerOfARunningCampaign().write(Chunk.of(m));

        InOrder order = inOrder(campaignRecipientRepository, rabbitTemplate);
        order.verify(campaignRecipientRepository).markQueued(eq(KEY), any(Instant.class));
        order.verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE, RabbitMQConfig.EMAIL_ROUTING_KEY, m);
    }

    @Test
    void writer_does_not_publish_a_row_that_another_run_already_queued_or_sent() {
        // A pause followed quickly by a resume can briefly overlap two runs that read the
        // same PENDING rows; the second claim finds the row QUEUED and gets 0.
        EmailMessage first = message(KEY);
        EmailMessage second = message("campaign_7_recipient_11");
        when(campaignRecipientRepository.markQueued(eq(KEY), any(Instant.class))).thenReturn(0);
        when(campaignRecipientRepository.markQueued(eq("campaign_7_recipient_11"), any(Instant.class))).thenReturn(1);

        writerOfARunningCampaign().write(Chunk.of(first, second));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), eq(first));
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE, RabbitMQConfig.EMAIL_ROUTING_KEY, second);
    }

    @Test
    void writer_rethrows_a_publish_failure_so_the_chunk_and_its_marks_roll_back() {
        EmailMessage m = message(KEY);
        when(campaignRecipientRepository.markQueued(eq(KEY), any(Instant.class))).thenReturn(1);
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
                .convertAndSend(RabbitMQConfig.EMAIL_EXCHANGE, RabbitMQConfig.EMAIL_ROUTING_KEY, m);

        CampaignItemWriter writer = writerOfARunningCampaign();
        assertThatThrownBy(() -> writer.write(Chunk.of(m)))
                .isInstanceOf(AmqpException.class);
    }

    // ── Consumer ────────────────────────────────────────────────────────────────

    @Mock private EmailProvider emailProvider;
    @Mock private RateLimiterService rateLimiter;
    @Mock private EmailLogService emailLogService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    private EmailConsumer consumer() {
        return new EmailConsumer(emailProvider, campaignRecipientRepository, campaignRepository,
                rateLimiter, emailLogService, redisTemplate);
    }

    private static Campaign campaignIn(CampaignStatus status) {
        return Campaign.builder().id(CAMPAIGN_ID).name("c").subject("s").status(status).build();
    }

    private void notSentBefore() {
        when(redisTemplate.hasKey("email:sent:" + KEY)).thenReturn(false);
    }

    private void sendingSucceeds() {
        when(rateLimiter.tryAcquire(CAMPAIGN_ID)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void message_of_a_paused_campaign_is_not_sent_and_its_row_goes_back_to_pending() {
        notSentBefore();
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(CampaignStatus.PAUSED));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaignIn(CampaignStatus.PAUSED)));
        EmailMessage m = message(KEY);

        consumer().consume(m);

        // Back to PENDING so the resumed job publishes it again; the rate-limit token is
        // not used and no counter or log entry is written.
        verify(campaignRecipientRepository).returnQueuedToPending(KEY);
        verifyNoInteractions(emailProvider, rateLimiter, emailLogService);
        verify(campaignRecipientRepository, never()).markSent(anyString(), any());
        verify(campaignRepository, never()).incrementCounters(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void message_is_sent_when_the_campaign_was_resumed_between_the_two_status_reads() {
        notSentBefore();
        sendingSucceeds();
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(CampaignStatus.PAUSED));
        // Re-read under the row lock: the resume committed first.
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaignIn(CampaignStatus.QUEUED)));

        consumer().consume(message(KEY));

        verify(emailProvider).send(any());
        verify(campaignRecipientRepository).markSent(eq(KEY), any(Instant.class));
        verify(campaignRecipientRepository, never()).returnQueuedToPending(anyString());
    }

    @Test
    void message_of_a_cancelled_campaign_is_skipped_not_sent() {
        notSentBefore();
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(CampaignStatus.CANCELLED));

        consumer().consume(message(KEY));

        verify(campaignRecipientRepository).skipQueued(eq(KEY), any(Instant.class), eq("Campaign cancelled"));
        verifyNoInteractions(emailProvider, rateLimiter, emailLogService);
        verify(campaignRecipientRepository, never()).markSent(anyString(), any());
    }

    @Test
    void message_of_a_campaign_that_no_longer_exists_is_not_sent() {
        notSentBefore();
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        consumer().consume(message(KEY));

        verify(campaignRecipientRepository).skipQueued(eq(KEY), any(Instant.class), eq("Campaign no longer exists"));
        verifyNoInteractions(emailProvider, rateLimiter, emailLogService);
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"QUEUED", "RUNNING", "COMPLETED", "FAILED"})
    void message_is_sent_while_the_campaign_is_sending_or_its_job_has_ended(CampaignStatus status) {
        // COMPLETED and FAILED: the job has finished publishing, but messages it already
        // published are still delivered.
        notSentBefore();
        sendingSucceeds();
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(status));

        consumer().consume(message(KEY));

        verify(emailProvider).send(any());
        verify(campaignRecipientRepository).markSent(eq(KEY), any(Instant.class));
        verify(campaignRepository).incrementCounters(eq(CAMPAIGN_ID), eq(1L), eq(0L), eq(0L), eq(0L), any(Instant.class));
        verify(campaignRepository, never()).findByIdForUpdate(anyLong());
        verify(campaignRecipientRepository, never()).returnQueuedToPending(anyString());
        verify(campaignRecipientRepository, never()).skipQueued(anyString(), any(), anyString());
    }

    @Test
    void an_already_sent_message_is_suppressed_before_the_status_is_read() {
        when(redisTemplate.hasKey("email:sent:" + KEY)).thenReturn(true);

        consumer().consume(message(KEY));

        verifyNoInteractions(campaignRepository, emailProvider, rateLimiter);
    }
}
