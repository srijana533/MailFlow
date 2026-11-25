package com.mailflow.email.repository;

import com.mailflow.email.entity.UnsubscribeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Every lookup is scoped to one organization: an opt-out applies to that company only. */
public interface UnsubscribeRepository extends JpaRepository<UnsubscribeEntry, Long> {

    boolean existsByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    Optional<UnsubscribeEntry> findByToken(String token);

    Optional<UnsubscribeEntry> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    /**
     * The given addresses that are on the organization's unsubscribe list (read-only).
     * Entries are stored normalised (see {@code EmailValidator.normalize}), so pass
     * normalised addresses.
     */
    @Query("SELECT u.email FROM UnsubscribeEntry u WHERE u.organizationId = :orgId AND u.email IN :emails")
    List<String> findEmailsIn(@Param("orgId") Long organizationId,
                              @Param("emails") Collection<String> emails);
}
