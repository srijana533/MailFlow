package com.mailflow.email.repository;

import com.mailflow.email.entity.UnsubscribeEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UnsubscribeRepository extends JpaRepository<UnsubscribeEntry, Long> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<UnsubscribeEntry> findByToken(String token);

    Optional<UnsubscribeEntry> findByEmailIgnoreCase(String email);
}
