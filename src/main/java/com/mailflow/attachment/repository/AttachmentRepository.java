package com.mailflow.attachment.repository;

import com.mailflow.attachment.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /** Attachment by ID as a query, so the organization filter applies (findById is not filtered). */
    @Query("SELECT a FROM Attachment a WHERE a.id = :id")
    Optional<Attachment> findOneById(@Param("id") Long id);
}