package com.mailflow.campaign.repository;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /**
     * Lists campaigns that are not deleted, optionally filtered by status and/or a
     * name search. {@code :search} is CAST to string so a null parameter is not
     * inferred as {@code bytea} by PostgreSQL (which has no {@code lower(bytea)} function).
     */
    @Query("""
           SELECT c FROM Campaign c
           WHERE c.deletedAt IS NULL
           AND (:status IS NULL OR c.status = :status)
           AND (CAST(:search AS string) IS NULL
                OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
           """)
    Page<Campaign> findAll(@Param("status") CampaignStatus status,
                           @Param("search") String search,
                           Pageable pageable);

    /**
     * Campaign by ID as a JPQL query, so Hibernate's organization filter applies (a plain
     * {@code findById} is a direct load and is not filtered). Use this for every web lookup.
     */
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findOneById(@Param("id") Long id);

    List<Campaign> findByStatusIn(List<CampaignStatus> statuses);

    /** Every campaign that is not deleted (the dashboard totals). */
    List<Campaign> findByDeletedAtIsNull();

    /**
     * Finds campaigns due for launch; deleted campaigns are never launched.
     * Uses a bound parameter for the status enum to avoid string-literal JPQL
     * which is unreliable across Hibernate versions.
     */
    @Query("""
           SELECT c FROM Campaign c
           WHERE c.status = :status AND c.scheduledAt <= :now AND c.deletedAt IS NULL
           """)
    List<Campaign> findDueScheduledCampaigns(
            @Param("status") CampaignStatus status,
            @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Campaign c SET c.status = :status, c.updatedAt = :now WHERE c.id = :id")
    int updateStatus(@Param("id") Long id,
                     @Param("status") CampaignStatus status,
                     @Param("now") Instant now);

    /**
     * Changes the status only while it is still one of {@code expected} and returns
     * the number of rows changed (0 or 1). The batch job listener uses it so a job
     * that finishes late never overwrites a campaign paused or cancelled meanwhile.
     */
    @Modifying
    @Query("""
           UPDATE Campaign c SET c.status = :status, c.updatedAt = :now
           WHERE c.id = :id AND c.status IN :expected
           """)
    int updateStatusIfIn(@Param("id") Long id,
                         @Param("status") CampaignStatus status,
                         @Param("expected") Collection<CampaignStatus> expected,
                         @Param("now") Instant now);

    /**
     * The current status read from the database, bypassing any entity already loaded.
     * The batch reader, processor and writer and the email consumer check it so a
     * paused, cancelled or deleted campaign stops sending. A deleted campaign gives
     * an empty result, the same as a campaign that doesn't exist.
     */
    @Query("SELECT c.status FROM Campaign c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<CampaignStatus> findStatusById(@Param("id") Long id);

    /**
     * Loads the campaign and locks its row until the transaction ends, so a
     * concurrent status change (for example a resume) waits for this transaction.
     * Used by the email consumer and by delete. Deleted campaigns are returned too;
     * callers check {@link Campaign#isDeleted()}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

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
