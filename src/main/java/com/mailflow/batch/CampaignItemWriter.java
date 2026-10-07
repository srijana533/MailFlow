package com.mailflow.batch;

import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
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
 * <p>This class is <em>not</em> annotated with {@code @Component} — it is
 * constructed by {@link CampaignBatchConfig} as a {@code @Bean @StepScope}.
 */
@Slf4j
@RequiredArgsConstructor
public class CampaignItemWriter implements ItemWriter<EmailMessage> {

    private final RabbitTemplate rabbitTemplate;
    private final CampaignRecipientRepository campaignRecipientRepository;

    @Override
    public void write(Chunk<? extends EmailMessage> chunk) {
        for (EmailMessage message : chunk) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EMAIL_EXCHANGE,
                        RabbitMQConfig.EMAIL_ROUTING_KEY,
                        message);

                // Mark as QUEUED with queuedAt timestamp
                campaignRecipientRepository.markQueued(
                        message.idempotencyKey(), Instant.now());

                log.debug("Queued email for {} (key={})",
                        message.toAddress(), message.idempotencyKey());

            } catch (Exception e) {
                log.error("Failed to queue email for {}: {}",
                        message.toAddress(), e.getMessage());
                throw e; // Let Spring Batch retry/skip policy handle it
            }
        }
        log.info("Chunk written: {} messages queued", chunk.size());
    }
}
