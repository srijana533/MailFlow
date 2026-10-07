package com.mailflow.recipient.service;

import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.recipient.dto.RecipientRequest;
import com.mailflow.recipient.dto.RecipientResponse;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Core CRUD service for recipients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientRepository recipientRepository;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "recipients", allEntries = true)
    public RecipientResponse create(RecipientRequest request) {
        validateEmail(request.email());

        if (recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(request.email())) {
            throw new ConflictException("Recipient already exists: " + request.email());
        }

        Recipient recipient = toEntity(request);
        recipient.setImportSource("API");
        return toResponse(recipientRepository.save(recipient));
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipientResponse> findAll(String search, RecipientStatus status, Pageable pageable) {
        Page<Recipient> page = recipientRepository.findAllActive(search, status, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "recipients", key = "#id")
    public RecipientResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "recipients", key = "#id")
    public RecipientResponse update(Long id, RecipientRequest request) {
        Recipient existing = getOrThrow(id);

        if (!existing.getEmail().equalsIgnoreCase(request.email())) {
            validateEmail(request.email());
            if (recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(request.email())) {
                throw new ConflictException("Email already in use: " + request.email());
            }
        }

        existing.setEmail(request.email().toLowerCase());
        existing.setName(request.name());
        existing.setFirstName(request.firstName());
        existing.setLastName(request.lastName());
        existing.setCompany(request.company());
        existing.setCourse(request.course());
        if (request.customFields() != null) {
            existing.setCustomFields(request.customFields());
        }
        return toResponse(recipientRepository.save(existing));
    }

    @Transactional
    @CacheEvict(value = "recipients", key = "#id")
    public void delete(Long id) {
        Recipient recipient = getOrThrow(id);
        recipient.setDeletedAt(Instant.now());
        recipientRepository.save(recipient);
        log.info("Soft-deleted recipient id={}", id);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private Recipient getOrThrow(Long id) {
        return recipientRepository.findById(id)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient", id));
    }

    private void validateEmail(String email) {
        if (!EmailValidator.isValid(email)) {
            throw new com.mailflow.common.exception.ValidationException(
                    "Invalid email address: " + email);
        }
    }

    private Recipient toEntity(RecipientRequest req) {
        return Recipient.builder()
                .email(req.email().trim().toLowerCase())
                .name(req.name())
                .firstName(req.firstName())
                .lastName(req.lastName())
                .company(req.company())
                .course(req.course())
                .customFields(req.customFields() != null ? req.customFields() : new java.util.HashMap<>())
                .status(RecipientStatus.ACTIVE)
                .build();
    }

    public RecipientResponse toResponse(Recipient r) {
        return new RecipientResponse(
            r.getId(), r.getEmail(), r.getName(),
            r.getFirstName(), r.getLastName(),
            r.getCompany(), r.getCourse(),
            r.getCustomFields(), r.getStatus(),
            r.getImportSource(), r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
