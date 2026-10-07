package com.mailflow.campaign.repository;

import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    boolean existsByCampaignIdAndRecipientId(Long campaignId, Long recipientId);

    Optional<CampaignRecipient> findByIdempotencyKey(String idempotencyKey);

    Page<CampaignRecipient> findByCampaignIdAndDeliveryStatus(
            Long campaignId, DeliveryStatus status, Pageable pageable);

    long countByCampaignIdAndDeliveryStatus(Long campaignId, DeliveryStatus status);

    long countByCampaignId(Long campaignId);

    /**
     * Marks a record as QUEUED after the batch writer publishes to RabbitMQ.
     * Sets {@code queuedAt} (not sentAt — that is set when SMTP delivery is confirmed).
     */
    @Modifying
    @Query("""
           UPDATE CampaignRecipient cr
           SET cr.deliveryStatus = 'QUEUED', cr.queuedAt = :now
           WHERE cr.idempotencyKey = :key
             AND cr.deliveryStatus NOT IN ('SENT', 'QUEUED')
           """)
    int markQueued(@Param("key") String idempotencyKey,
                   @Param("now") Instant now);

    /**
     * Marks a record as SENT after the email consumer confirms delivery.
     */
    @Modifying
    @Query("""
           UPDATE CampaignRecipient cr
           SET cr.deliveryStatus = 'SENT', cr.sentAt = :now
           WHERE cr.idempotencyKey = :key
             AND cr.deliveryStatus != 'SENT'
           """)
    int markSent(@Param("key") String idempotencyKey,
                 @Param("now") Instant now);

    /**
     * Marks a record as FAILED or SKIPPED and records the error reason.
     */
    @Modifying
    @Query("""
           UPDATE CampaignRecipient cr
           SET cr.deliveryStatus = :status,
               cr.failedAt = :now,
               cr.lastError = :error,
               cr.retryCount = cr.retryCount + 1
           WHERE cr.idempotencyKey = :key
           """)
    int markFailed(@Param("key") String idempotencyKey,
                   @Param("status") DeliveryStatus status,
                   @Param("now") Instant now,
                   @Param("error") String error);
}
