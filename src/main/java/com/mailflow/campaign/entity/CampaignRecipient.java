package com.mailflow.campaign.entity;

import com.mailflow.recipient.entity.Recipient;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-recipient delivery state within a campaign.
 *
 * <p>The {@code idempotencyKey} is a globally unique delivery identifier
 * (format: {@code campaign_{campaignId}_recipient_{recipientId}}) that
 * prevents duplicate email sends even if the worker restarts or a message
 * is redelivered from RabbitMQ.
 */
@Entity
@Table(name = "campaign_recipients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Recipient recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    @Builder.Default
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "retry_count")
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "personalized_subject")
    private String personalizedSubject;

    public enum DeliveryStatus {
        PENDING, QUEUED, SENT, FAILED, SKIPPED, BOUNCED
    }

    public static String buildIdempotencyKey(Long campaignId, Long recipientId) {
        return "campaign_" + campaignId + "_recipient_" + recipientId;
    }
}
