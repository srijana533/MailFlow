package com.mailflow.analytics.repository;

import com.mailflow.analytics.entity.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    Page<EmailLog> findByCampaignId(Long campaignId, Pageable pageable);

    long countByCampaignIdAndStatus(Long campaignId, String status);

    /** Aggregate delivery counts by status for a single campaign (JPQL — fine). */
    @Query("SELECT l.status, COUNT(l) FROM EmailLog l WHERE l.campaignId = :id GROUP BY l.status")
    List<Object[]> countByStatusForCampaign(@Param("id") Long campaignId);

    /**
     * Daily sent-email volume using native PostgreSQL DATE_TRUNC. Emails of deleted
     * campaigns are left out, like the dashboard totals; their log rows are kept.
     *
     * <p>Must be {@code nativeQuery = true} because {@code DATE_TRUNC} is a
     * PostgreSQL function that JPQL/Hibernate does not understand.
     */
    @Query(value = """
           SELECT DATE_TRUNC('day', l.processed_at) AS volume_day, COUNT(*) AS cnt
           FROM email_logs l
           JOIN campaigns c ON c.id = l.campaign_id
           WHERE l.processed_at >= :from
             AND l.processed_at <= :to
             AND l.status = 'SENT'
             AND c.deleted_at IS NULL
             AND c.organization_id = :orgId
           GROUP BY DATE_TRUNC('day', l.processed_at)
           ORDER BY 1
           """,
           nativeQuery = true)
    List<Object[]> dailyVolume(@Param("orgId") Long organizationId,
                               @Param("from") Instant from, @Param("to") Instant to);
}
