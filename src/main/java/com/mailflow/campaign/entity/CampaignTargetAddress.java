package com.mailflow.campaign.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A campaign-only address: an address a campaign emails without it being a saved
 * contact (typed in, or a new row from an uploaded file). It is stored with the
 * campaign and never added to the {@code recipients} table, so other campaigns
 * can't reuse it. Deleting the campaign (a soft delete) removes these rows
 * explicitly; the delivery rows of a campaign that was sent keep their own snapshot
 * of each address as delivery history.
 *
 * <p>At launch each address is resolved by {@code CampaignService}: an address that
 * has since become a saved contact uses that contact and its status, addresses of
 * deleted contacts and unsubscribed addresses are skipped, and the rest get a
 * {@link CampaignRecipient} row with no recipient and a snapshot of these values.
 */
@Entity
@Table(name = "campaign_target_addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignTargetAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    /** Lower-case address (enforced by a CHECK constraint). */
    @Column(nullable = false)
    private String email;

    private String name;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String company;

    /** Extra personalisation values ({{Team}}, {{role}}, …), mapped like Recipient.customFields. */
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private Map<String, String> customFields = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
