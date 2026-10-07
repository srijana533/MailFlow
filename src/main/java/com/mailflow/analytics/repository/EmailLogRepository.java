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
     * Daily sent-email volume using native PostgreSQL DATE_TRUNC.
     *
     * <p>Must be {@code nativeQuery = true} because {@code DATE_TRUNC} is a
     * PostgreSQL function that JPQL/Hibernate does not understand.
     */
    @Query(value = """
           SELECT DATE_TRUNC('day', processed_at) AS day, COUNT(*) AS cnt
           FROM email_logs
           WHERE processed_at >= :from
             AND processed_at <= :to
             AND status = 'SENT'
           GROUP BY DATE_TRUNC('day', processed_at)
           ORDER BY 1
           """,
           nativeQuery = true)
    List<Object[]> dailyVolume(@Param("from") Instant from, @Param("to") Instant to);
}
