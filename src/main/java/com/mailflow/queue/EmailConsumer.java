package com.mailflow.queue;

import com.mailflow.analytics.EmailLogService;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import com.mailflow.config.RabbitMQConfig;
import com.mailflow.email.EmailProvider;
import com.mailflow.email.EmailRequest;
import com.mailflow.email.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ consumer that performs the actual email send.
 *
 * <p>Idempotency: before sending, checks Redis for the idempotency key.
 * If the key exists the message is a duplicate and is silently acked.
 *
 * <p>Rate limiting: token bucket via Redis.  If the limit is exceeded a
 * {@link RateLimitExceededException} is thrown, which causes the RabbitMQ
 * retry interceptor to requeue the message after a backoff.
 *
 * <p>Permanent failures (5xx SMTP rejection) are caught and recorded; the
 * message is acked so it is not infinitely redelivered.  Temporary failures
 * propagate up to the retry interceptor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailConsumer {

    private static final String IDEMPOTENCY_PREFIX  = "email:sent:";
    private static final long   IDEMPOTENCY_TTL_HRS = 48;

    private final EmailProvider emailProvider;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignRepository campaignRepository;
    private final RateLimiterService rateLimiter;
    private final EmailLogService emailLogService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional
    @RabbitListener(
            queues = RabbitMQConfig.EMAIL_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void consume(EmailMessage message) {
        String redisKey = IDEMPOTENCY_PREFIX + message.idempotencyKey();

        // ── Idempotency guard ──────────────────────────────────────────
        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
            log.debug("Duplicate suppressed: {}", message.idempotencyKey());
            return;
        }

        // ── Rate limiting ──────────────────────────────────────────────
        if (!rateLimiter.tryAcquire(message.campaignId())) {
            log.warn("Rate limit exceeded for campaign {}. Requeuing.", message.campaignId());
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        // ── Send ───────────────────────────────────────────────────────
        try {
            emailProvider.send(EmailRequest.builder()
                    .to(message.toAddress())
                    .from(message.senderEmail())
                    .fromName(message.senderName())
                    .replyTo(message.replyTo())
                    .subject(message.subject())
                    .htmlBody(message.htmlBody())
                    .plainBody(message.plainBody())
                    .build());

            // Write idempotency key to Redis so re-deliveries are suppressed
            redisTemplate.opsForValue().set(redisKey, "1",
                    IDEMPOTENCY_TTL_HRS, TimeUnit.HOURS);

            // Update DB + campaign counters
            campaignRecipientRepository.markSent(
                    message.idempotencyKey(), Instant.now());
            campaignRepository.incrementCounters(
                    message.campaignId(), 1, 0, 0, 0, Instant.now());

            emailLogService.logSuccess(message);
            log.info("Email sent to {} (campaign={})",
                    message.toAddress(), message.campaignId());

        } catch (PermanentEmailException e) {
            // 5xx / invalid address — do NOT retry, ack the message
            log.error("Permanent failure for {}: {}", message.toAddress(), e.getMessage());
            campaignRecipientRepository.markFailed(
                    message.idempotencyKey(), DeliveryStatus.FAILED,
                    Instant.now(), e.getMessage());
            campaignRepository.incrementCounters(
                    message.campaignId(), 0, 1, 0, 0, Instant.now());
            emailLogService.logFailure(message, e.getMessage(), "PERMANENT");
            // Do NOT rethrow — ack the message to prevent infinite retry
        }
        // Temporary exceptions (MailException, ConnectException, RateLimitExceededException)
        // propagate up to the retry interceptor which requeues with exponential backoff.
    }
}
