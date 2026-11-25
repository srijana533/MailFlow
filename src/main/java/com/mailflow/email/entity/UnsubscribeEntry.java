package com.mailflow.email.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An address that opted out of one organization's emails. Opting out of one
 * company does not block another company from emailing the same address.
 *
 * <p>The organization is an explicit column, not a Hibernate tenant filter: entries
 * are read and written by the batch job and by public unsubscribe links, which have
 * no signed-in user. Every query passes the organization.
 */
@Entity
@Table(name = "unsubscribe_list",
       uniqueConstraints = @UniqueConstraint(name = "uq_unsubscribe_org_email",
                                             columnNames = {"organization_id", "email"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnsubscribeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "unsubscribed_at", nullable = false)
    private Instant unsubscribedAt;

    @Column(name = "campaign_id")
    private Long campaignId;
}
