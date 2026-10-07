package com.mailflow.campaign.repository;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /**
     * Lists campaigns, optionally filtered by status and/or a name search.
     * {@code :search} is CAST to string so a null parameter is not inferred as
     * {@code bytea} by PostgreSQL (which has no {@code lower(bytea)} function).
     */
    @Query("""
           SELECT c FROM Campaign c
           WHERE (:status IS NULL OR c.status = :status)
           AND (CAST(:search AS string) IS NULL
                OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
           """)
    Page<Campaign> findAll(@Param("status") CampaignStatus status,
                           @Param("search") String search,
                           Pageable pageable);

    List<Campaign> findByStatusIn(List<CampaignStatus> statuses);

    /**
     * Finds campaigns due for launch.
     * Uses a bound parameter for the status enum to avoid string-literal JPQL
     * which is unreliable across Hibernate versions.
     */
    @Query("SELECT c FROM Campaign c WHERE c.status = :status AND c.scheduledAt <= :now")
    List<Campaign> findDueScheduledCampaigns(
            @Param("status") CampaignStatus status,
            @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Campaign c SET c.status = :status, c.updatedAt = :now WHERE c.id = :id")
    int updateStatus(@Param("id") Long id,
                     @Param("status") CampaignStatus status,
                     @Param("now") Instant now);

    @Modifying
    @Query("""
           UPDATE Campaign c SET
               c.sentCount    = c.sentCount + :sent,
               c.failedCount  = c.failedCount + :failed,
               c.skippedCount = c.skippedCount + :skipped,
               c.retryCount   = c.retryCount + :retries,
               c.updatedAt    = :now
           WHERE c.id = :id
           """)
    void incrementCounters(@Param("id") Long id,
                           @Param("sent") long sent,
                           @Param("failed") long failed,
                           @Param("skipped") long skipped,
                           @Param("retries") long retries,
                           @Param("now") Instant now);
}
