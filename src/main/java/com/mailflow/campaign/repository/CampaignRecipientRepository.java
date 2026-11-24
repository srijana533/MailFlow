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
import java.util.List;
import java.util.Optional;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    boolean existsByCampaignIdAndRecipientId(Long campaignId, Long recipientId);

    /**
     * Lower-case addresses that already have a delivery row in this campaign: the
     * saved contact's email for contact rows, the snapshot email for campaign-only
     * rows. A relaunch (from PAUSED or FAILED) uses it so no address gets a second row.
     */
    @Query("""
           SELECT LOWER(COALESCE(r.email, cr.snapshotEmail)) FROM CampaignRecipient cr
           LEFT JOIN cr.recipient r
           WHERE cr.campaign.id = :campaignId
           """)
    List<String> findTargetedEmails(@Param("campaignId") Long campaignId);

    Optional<CampaignRecipient> findByIdempotencyKey(String idempotencyKey);

    Page<CampaignRecipient> findByCampaignIdAndDeliveryStatus(
            Long campaignId, DeliveryStatus status, Pageable pageable);

    long countByCampaignIdAndDeliveryStatus(Long campaignId, DeliveryStatus status);

    long countByCampaignId(Long campaignId);

    /**
     * QUEUED rows of a campaign queued at or after {@code since}: rows whose message
     * may still be waiting in RabbitMQ. Delete refuses while there are any. Older
     * QUEUED rows (or ones without a queue time) can't have a message left in
     * {@code email.queue}, whose messages expire after 24 hours.
     */
    @Query("""
           SELECT COUNT(cr) FROM CampaignRecipient cr
           WHERE cr.campaign.id = :campaignId
             AND cr.deliveryStatus = 'QUEUED'
             AND cr.queuedAt >= :since
           """)
    long countQueuedSince(@Param("campaignId") Long campaignId, @Param("since") Instant since);

    /** Queue time of the newest row counted by {@link #countQueuedSince}; empty when there is none. */
    @Query("""
           SELECT MAX(cr.queuedAt) FROM CampaignRecipient cr
           WHERE cr.campaign.id = :campaignId
             AND cr.deliveryStatus = 'QUEUED'
             AND cr.queuedAt >= :since
           """)
    Optional<Instant> findLatestQueuedAtSince(@Param("campaignId") Long campaignId, @Param("since") Instant since);

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
     * Puts a QUEUED record back to PENDING when the consumer holds its message
     * because the campaign is paused; resuming publishes it again. Rows in any other
     * status (for example SENT) are left alone.
     */
    @Modifying
    @Query("""
           UPDATE CampaignRecipient cr
           SET cr.deliveryStatus = 'PENDING', cr.queuedAt = NULL
           WHERE cr.idempotencyKey = :key
             AND cr.deliveryStatus = 'QUEUED'
           """)
    int returnQueuedToPending(@Param("key") String idempotencyKey);

    /**
     * Marks a QUEUED record SKIPPED with a reason, for a message the consumer drops
     * without sending (for example because the campaign was cancelled). Rows in any
     * other status (for example SENT) are left alone.
     */
    @Modifying
    @Query("""
           UPDATE CampaignRecipient cr
           SET cr.deliveryStatus = 'SKIPPED', cr.failedAt = :now, cr.lastError = :reason
           WHERE cr.idempotencyKey = :key
             AND cr.deliveryStatus = 'QUEUED'
           """)
    int skipQueued(@Param("key") String idempotencyKey,
                   @Param("now") Instant now,
                   @Param("reason") String reason);

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
