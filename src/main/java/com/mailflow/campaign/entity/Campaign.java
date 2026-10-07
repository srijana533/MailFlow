package com.mailflow.campaign.entity;

import com.mailflow.auth.entity.User;
import com.mailflow.template.entity.EmailTemplate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * An email campaign ties together a template, a set of recipients,
 * configuration, and tracks all processing metrics.
 *
 * <p>Optimistic locking ({@code @Version}) prevents concurrent
 * status transitions from clobbering each other.
 */
@Entity
@Table(name = "campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private EmailTemplate template;

    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "sender_email")
    private String senderEmail;

    @Column(name = "reply_to")
    private String replyTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ElementCollection
    @CollectionTable(name = "campaign_target_groups", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "group_id")
    @Builder.Default
    private Set<Long> recipientGroupIds = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "campaign_target_recipients", joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "recipient_id")
    @Builder.Default
    private Set<Long> recipientIds = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Processing counters ─────────────────────────────────────────────────

    @Column(name = "total_recipients")
    @Builder.Default
    private long totalRecipients = 0;

    @Column(name = "sent_count")
    @Builder.Default
    private long sentCount = 0;

    @Column(name = "failed_count")
    @Builder.Default
    private long failedCount = 0;

    @Column(name = "skipped_count")
    @Builder.Default
    private long skippedCount = 0;

    @Column(name = "bounced_count")
    @Builder.Default
    private long bouncedCount = 0;

    @Column(name = "retry_count")
    @Builder.Default
    private long retryCount = 0;

    @Column(name = "batch_job_id")
    private String batchJobId;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0;

    // ── Computed helpers ────────────────────────────────────────────────────

    public double successRate() {
        if (totalRecipients == 0) return 0.0;
        return (double) sentCount / totalRecipients * 100.0;
    }

    public boolean isRunnable() {
        return status == CampaignStatus.DRAFT || status == CampaignStatus.SCHEDULED
               || status == CampaignStatus.PAUSED || status == CampaignStatus.FAILED;
    }

    public boolean isPauseable() {
        return status == CampaignStatus.RUNNING;
    }

    public boolean isCancellable() {
        return status == CampaignStatus.DRAFT || status == CampaignStatus.SCHEDULED
               || status == CampaignStatus.QUEUED || status == CampaignStatus.RUNNING
               || status == CampaignStatus.PAUSED;
    }
}
