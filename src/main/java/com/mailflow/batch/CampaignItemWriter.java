package com.mailflow.batch;

import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.config.RabbitMQConfig;
import com.mailflow.queue.EmailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.time.Instant;

/**
 * Publishes personalised {@link EmailMessage}s to RabbitMQ and marks each
 * campaign-recipient record as {@code QUEUED}.
 *
 * <p>Each record is marked QUEUED <em>before</em> its message is published, and a
 * record that is already QUEUED or SENT is not published again. Two runs of the same
 * campaign can overlap briefly (a pause followed quickly by a resume); the row update
 * makes the second run wait for the first one's chunk and then skip its rows, so no
 * address gets two messages. If publishing fails, the chunk transaction rolls the
 * marks back.
 *
 * <p>Before a chunk is written the campaign status is read again: a campaign that is
 * no longer RUNNING (paused, cancelled or deleted while the chunk was processed) gets
 * nothing published, and its rows stay PENDING.
 *
 * <p>This class is <em>not</em> annotated with {@code @Component} — it is
 * constructed by {@link CampaignBatchConfig} as a {@code @Bean @StepScope}.
 */
@Slf4j
@RequiredArgsConstructor
public class CampaignItemWriter implements ItemWriter<EmailMessage> {

    private final RabbitTemplate rabbitTemplate;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignRepository campaignRepository;

    @Override
    public void write(Chunk<? extends EmailMessage> chunk) {
        if (chunk.isEmpty()) return;

        // A job (and so a chunk) belongs to one campaign.
        Long campaignId = chunk.getItems().get(0).campaignId();
        CampaignStatus status = campaignRepository.findStatusById(campaignId).orElse(null);
        if (status != CampaignStatus.RUNNING) {
            log.info("Campaign {} is {}: {} messages not queued",
                    campaignId, status != null ? status : "deleted", chunk.size());
            return;
        }

        int published = 0;
        for (EmailMessage message : chunk) {
            // Claim the record first (sets QUEUED and queuedAt); 0 means another run
            // already queued or sent it.
            if (campaignRecipientRepository.markQueued(message.idempotencyKey(), Instant.now()) == 0) {
                log.debug("Not queued again, already queued or sent: key={}", message.idempotencyKey());
                continue;
            }
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EMAIL_EXCHANGE,
                        RabbitMQConfig.EMAIL_ROUTING_KEY,
                        message);
                published++;

                log.debug("Queued email for {} (key={})",
                        message.toAddress(), message.idempotencyKey());

            } catch (Exception e) {
                log.error("Failed to queue email for {}: {}",
                        message.toAddress(), e.getMessage());
                throw e; // Let Spring Batch retry/skip policy handle it
            }
        }
        log.info("Chunk written: {} of {} messages queued", published, chunk.size());
    }
}
