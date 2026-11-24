package com.mailflow.queue;

import com.mailflow.analytics.EmailLogService;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
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
 *
 * <p>Campaign status: before sending, the consumer reads the campaign's current
 * status. A message of a PAUSED campaign is not sent; its record goes back to
 * PENDING so that resuming publishes it again. A message of a CANCELLED, deleted
 * (or no longer existing) campaign is not sent; its record is marked SKIPPED with
 * the reason. A message whose status was read just before a pause, cancel or delete
 * is still sent.
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

        // ── Campaign still sending? ────────────────────────────────────
        if (!mayStillSend(message)) {
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

    /**
     * Whether this message may be sent now. For a paused campaign the record goes
     * back to PENDING; for a cancelled, deleted or missing campaign it is marked
     * SKIPPED with the reason. Either way the message is acked without sending.
     */
    private boolean mayStillSend(EmailMessage message) {
        // Empty for a deleted campaign as well as for one that doesn't exist.
        CampaignStatus status = campaignRepository.findStatusById(message.campaignId()).orElse(null);

        if (status == CampaignStatus.PAUSED) {
            // Re-read under a row lock: a resume committing in between either finished
            // first (then the status is no longer PAUSED and the email is sent) or waits
            // until this record is PENDING again, so the resumed job reads it.
            status = campaignRepository.findByIdForUpdate(message.campaignId())
                    .filter(c -> !c.isDeleted())
                    .map(Campaign::getStatus).orElse(null);
            if (status == CampaignStatus.PAUSED) {
                campaignRecipientRepository.returnQueuedToPending(message.idempotencyKey());
                log.info("Campaign {} is paused: email not sent, back to pending (key={})",
                        message.campaignId(), message.idempotencyKey());
                return false;
            }
        }

        if (status == null || status == CampaignStatus.CANCELLED) {
            String reason = status == CampaignStatus.CANCELLED ? "Campaign cancelled"
                    : campaignRepository.existsById(message.campaignId()) ? "Campaign deleted"
                    : "Campaign no longer exists";
            campaignRecipientRepository.skipQueued(message.idempotencyKey(), Instant.now(), reason);
            log.info("Email not sent: {} (campaign={}, key={})",
                    reason, message.campaignId(), message.idempotencyKey());
            return false;
        }
        return true;
    }
}
