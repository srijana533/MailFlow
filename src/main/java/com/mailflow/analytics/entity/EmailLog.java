package com.mailflow.analytics.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "email_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "recipient_id")
    private Long recipientId;

    @Column(name = "campaign_recipient_id")
    private Long campaignRecipientId;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(nullable = false, length = 50)
    private String status; // SENT | FAILED | RETRYING | SKIPPED

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_type", length = 100)
    private String errorType; // TEMPORARY | PERMANENT

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
