package com.mailflow.recipient.repository;

import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecipientRepository extends JpaRepository<Recipient, Long> {

    Optional<Recipient> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    /**
     * Non-deleted recipients, optionally filtered by status and a search that matches
     * the email, name, first name, last name, "first last" or company.
     */
    @Query("""
           SELECT r FROM Recipient r
           WHERE r.deletedAt IS NULL
           AND (CAST(:search AS string) IS NULL
                OR LOWER(r.email)     LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                OR LOWER(r.name)      LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                OR LOWER(r.firstName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                OR LOWER(r.lastName)  LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                OR LOWER(CONCAT(COALESCE(r.firstName, ''), ' ', COALESCE(r.lastName, '')))
                                      LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                OR LOWER(r.company)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
           AND (:status IS NULL OR r.status = :status)
           """)
    Page<Recipient> findAllActive(
            @Param("search") String search,
            @Param("status") RecipientStatus status,
            Pageable pageable);

    @Query("SELECT r FROM Recipient r WHERE r.email IN :emails AND r.deletedAt IS NULL")
    List<Recipient> findByEmailIn(@Param("emails") List<String> emails);

    @Query("SELECT r.email FROM Recipient r WHERE r.email IN :emails AND r.deletedAt IS NULL")
    List<String> findExistingEmails(@Param("emails") List<String> emails);

    /**
     * Emails that belong to soft-deleted contacts. The email column is unique
     * across deleted and live rows, so these addresses cannot be re-created and
     * must be reported instead of failing an insert.
     */
    @Query("SELECT r.email FROM Recipient r WHERE r.email IN :emails AND r.deletedAt IS NOT NULL")
    List<String> findDeletedEmails(@Param("emails") List<String> emails);

    /** Non-deleted recipients by ID, in any status (used to reload a draft's selection). */
    List<Recipient> findByIdInAndDeletedAtIsNull(java.util.Collection<Long> ids);

    /**
     * Finds active, subscribed recipients eligible for a campaign.
     * Uses a bound parameter for the status enum to avoid unreliable string literals.
     */
    @Query("""
           SELECT r FROM Recipient r
           WHERE r.status = :activeStatus
           AND r.deletedAt IS NULL
           AND r.email NOT IN (
               SELECT sub.email FROM com.mailflow.email.entity.UnsubscribeEntry sub
           )
           """)
    Page<Recipient> findEligibleRecipients(
            @Param("activeStatus") RecipientStatus activeStatus,
            Pageable pageable);

    long countByStatusAndDeletedAtIsNull(RecipientStatus status);

    long countByDeletedAtIsNull();

    @Query("""
           SELECT DISTINCT r FROM Recipient r
           JOIN RecipientGroup g ON r MEMBER OF g.recipients
           WHERE g.id IN :groupIds
           AND r.deletedAt IS NULL
           AND r.status = 'ACTIVE'
           """)
    List<Recipient> findByGroupIds(@Param("groupIds") java.util.Collection<Long> groupIds);

    @Query("""
           SELECT r FROM Recipient r
           WHERE r.id IN :ids
           AND r.deletedAt IS NULL
           AND r.status = 'ACTIVE'
           """)
    List<Recipient> findByIdsIn(@Param("ids") java.util.Collection<Long> ids);

    @Query("""
           SELECT r FROM Recipient r
           WHERE r.deletedAt IS NULL
           AND r.status = 'ACTIVE'
           """)
    List<Recipient> findAllActiveRecipients();
}
