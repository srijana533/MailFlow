package com.mailflow.email.repository;

import com.mailflow.email.entity.UnsubscribeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UnsubscribeRepository extends JpaRepository<UnsubscribeEntry, Long> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<UnsubscribeEntry> findByToken(String token);

    Optional<UnsubscribeEntry> findByEmailIgnoreCase(String email);

    /**
     * The given addresses that are on the unsubscribe list (read-only). Entries are
     * stored normalised (see {@code EmailValidator.normalize}), so pass normalised
     * addresses.
     */
    @Query("SELECT u.email FROM UnsubscribeEntry u WHERE u.email IN :emails")
    List<String> findEmailsIn(@Param("emails") Collection<String> emails);
}
