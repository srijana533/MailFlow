package com.mailflow.campaign.entity;

import com.mailflow.common.util.EmailValidator;
import com.mailflow.recipient.entity.Recipient;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Per-recipient delivery state within a campaign.
 *
 * <p>A row delivers either to a saved contact ({@code recipient} set) or to a
 * campaign-only address ({@code recipient} null). A campaign-only row carries a
 * snapshot of the address ({@code snapshot*} fields) that is used to address and
 * personalise the email; saved-contact rows leave the snapshot empty and use the
 * contact's current data.
 *
 * <p>The {@code idempotencyKey} is a globally unique delivery identifier that
 * prevents duplicate email sends even if the worker restarts or a message is
 * redelivered from RabbitMQ. Formats: {@code campaign_{campaignId}_recipient_{recipientId}}
 * for saved contacts, {@code campaign_{campaignId}_addr_{sha256(email)}} for
 * campaign-only addresses.
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

    /** The saved contact, or null for a campaign-only address. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
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

    // ── Snapshot of a campaign-only address (only when recipient is null) ──────

    @Column(name = "email")
    private String snapshotEmail;

    @Column(name = "name")
    private String snapshotName;

    @Column(name = "first_name")
    private String snapshotFirstName;

    @Column(name = "last_name")
    private String snapshotLastName;

    @Column(name = "company")
    private String snapshotCompany;

    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> snapshotCustomFields;

    public enum DeliveryStatus {
        PENDING, QUEUED, SENT, FAILED, SKIPPED, BOUNCED
    }

    /** The address this row delivers to: the saved contact's email, else the snapshot. */
    public String targetEmail() {
        return recipient != null ? recipient.getEmail() : snapshotEmail;
    }

    public static String buildIdempotencyKey(Long campaignId, Long recipientId) {
        return "campaign_" + campaignId + "_recipient_" + recipientId;
    }

    /**
     * Key for a campaign-only address. The address is normalised
     * ({@link EmailValidator#normalize}) and hashed (SHA-256, hex) so the key never
     * contains the address itself (it is also used in RabbitMQ messages and Redis
     * keys), is the same for every spelling of one address, and always fits the
     * VARCHAR(100) column: at most 9 + 19 + 6 + 64 = 98 characters.
     */
    public static String buildAddressIdempotencyKey(Long campaignId, String email) {
        return "campaign_" + campaignId + "_addr_" + sha256Hex(EmailValidator.normalize(email));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to support SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
