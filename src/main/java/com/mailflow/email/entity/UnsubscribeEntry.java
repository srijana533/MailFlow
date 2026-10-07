package com.mailflow.email.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "unsubscribe_list")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnsubscribeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
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
