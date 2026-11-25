package com.mailflow.recipient.service;

import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.recipient.dto.RecipientLookupRequest;
import com.mailflow.recipient.dto.RecipientLookupResponse;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core CRUD service for recipients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientRepository recipientRepository;
    private final UnsubscribeRepository unsubscribeRepository;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    /** Emails are normalised ({@link EmailValidator#normalize}) before they are validated, checked and stored. */
    @Transactional
    @CacheEvict(value = "recipients", allEntries = true)
    public RecipientResponse create(RecipientRequest request) {
        String email = EmailValidator.normalize(request.email());
        validateEmail(email);

        if (recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(email)) {
            throw new ConflictException("Recipient already exists: " + email);
        }

        Recipient recipient = toEntity(request, email);
        recipient.setImportSource("API");
        return toResponse(recipientRepository.save(recipient));
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipientResponse> findAll(String search, RecipientStatus status, Pageable pageable) {
        Page<Recipient> page = recipientRepository.findAllActive(search, status, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "recipients", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public RecipientResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "recipients", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public RecipientResponse update(Long id, RecipientRequest request) {
        Recipient existing = getOrThrow(id);
        String email = EmailValidator.normalize(request.email());

        if (!Objects.equals(EmailValidator.normalize(existing.getEmail()), email)) {
            validateEmail(email);
            if (recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(email)) {
                throw new ConflictException("Email already in use: " + email);
            }
        }

        existing.setEmail(email);
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
    @CacheEvict(value = "recipients", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public void delete(Long id) {
        Recipient recipient = getOrThrow(id);
        recipient.setDeletedAt(Instant.now());
        recipientRepository.save(recipient);
        log.info("Soft-deleted recipient id={}", id);
    }

    // ── Lookup (read-only) ───────────────────────────────────────────────────

    /**
     * Finds saved contacts by ID and/or email without creating or changing anything.
     * Emails are normalised ({@link EmailValidator#normalize}; contacts are stored
     * normalised); invalid emails are ignored. Soft-deleted contacts are never
     * returned, but their emails are reported in {@code deletedEmails}; addresses on
     * the unsubscribe list are reported in {@code unsubscribedEmails}. Earlier
     * campaigns play no part: a contact can be used by any number of campaigns.
     */
    @Transactional(readOnly = true)
    public RecipientLookupResponse lookup(RecipientLookupRequest request) {
        Map<Long, Recipient> found = new LinkedHashMap<>();

        if (request.ids() != null && !request.ids().isEmpty()) {
            Set<Long> ids = request.ids().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!ids.isEmpty()) {
                for (Recipient r : recipientRepository.findByIdInAndDeletedAtIsNull(ids)) {
                    found.put(r.getId(), r);
                }
            }
        }

        List<String> deletedEmails = List.of();
        List<String> unsubscribedEmails = List.of();
        if (request.emails() != null && !request.emails().isEmpty()) {
            List<String> emails = request.emails().stream()
                    .filter(Objects::nonNull)
                    .map(EmailValidator::normalize)
                    .filter(EmailValidator::isValid)
                    .distinct()
                    .toList();
            if (!emails.isEmpty()) {
                Long orgId = com.mailflow.tenant.TenantContext.requireOrganizationId();
                for (Recipient r : recipientRepository.findByEmailIn(orgId, emails)) {
                    found.putIfAbsent(r.getId(), r);
                }
                deletedEmails = recipientRepository.findDeletedEmails(orgId, emails);
                unsubscribedEmails = unsubscribeRepository.findEmailsIn(orgId, emails);
            }
        }

        return new RecipientLookupResponse(
                found.values().stream().map(this::toResponse).toList(),
                deletedEmails,
                unsubscribedEmails);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private Recipient getOrThrow(Long id) {
        return recipientRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient", id));
    }

    private void validateEmail(String email) {
        if (!EmailValidator.isValid(email)) {
            throw new com.mailflow.common.exception.ValidationException(
                    "Invalid email address: " + email);
        }
    }

    private Recipient toEntity(RecipientRequest req, String normalizedEmail) {
        return Recipient.builder()
                .email(normalizedEmail)
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
