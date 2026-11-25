package com.mailflow.recipient.repository;

import com.mailflow.recipient.entity.RecipientGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecipientGroupRepository extends JpaRepository<RecipientGroup, Long> {

    List<RecipientGroup> findAllByOrderByCreatedAtDesc();

    /** Group by ID as a query, so the organization filter applies (findById is not filtered). */
    @Query("SELECT g FROM RecipientGroup g WHERE g.id = :id")
    Optional<RecipientGroup> findOneById(@Param("id") Long id);
}