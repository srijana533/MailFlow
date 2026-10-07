package com.mailflow.template.repository;

import com.mailflow.template.entity.EmailTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    @Query("""
           SELECT t FROM EmailTemplate t
           WHERE t.deletedAt IS NULL
           AND (CAST(:search AS string) IS NULL
                OR LOWER(t.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
           """)
    Page<EmailTemplate> findAllActive(@Param("search") String search, Pageable pageable);

    Optional<EmailTemplate> findByIdAndDeletedAtIsNull(Long id);
}
