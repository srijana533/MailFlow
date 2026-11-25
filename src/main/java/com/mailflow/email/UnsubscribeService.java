package com.mailflow.email;

import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.common.util.SlugUtils;
import com.mailflow.email.entity.UnsubscribeEntry;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The unsubscribe list, kept per organization: an address that opts out of one
 * company's emails can still be emailed by another company. Addresses are normalised
 * ({@link EmailValidator#normalize}) before they are stored, looked up or used in a
 * cache key, so an unsubscribe made with different case or surrounding whitespace
 * still matches the address used when sending.
 *
 * <p>Callers pass the organization explicitly: the batch job has no signed-in user,
 * so it passes the campaign's organization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    private final UnsubscribeRepository unsubscribeRepository;
    private final RecipientRepository recipientRepository;

    @Cacheable(value = "unsubscribed",
               key = "#organizationId + ':' + T(com.mailflow.common.util.EmailValidator).normalize(#email)")
    public boolean isUnsubscribed(Long organizationId, String email) {
        return unsubscribeRepository.existsByOrganizationIdAndEmailIgnoreCase(
                organizationId, EmailValidator.normalize(email));
    }

    @Transactional
    @CacheEvict(value = "unsubscribed",
                key = "#organizationId + ':' + T(com.mailflow.common.util.EmailValidator).normalize(#email)")
    public String unsubscribe(Long organizationId, String email, String reason, Long campaignId) {
        String normalized = EmailValidator.normalize(email);
        var existing = unsubscribeRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, normalized);
        if (existing.isPresent()) {
            // Already unsubscribed — idempotent
            return existing.get().getToken();
        }

        String token = SlugUtils.generateToken();
        unsubscribeRepository.save(UnsubscribeEntry.builder()
                .organizationId(organizationId)
                .email(normalized)
                .token(token)
                .reason(reason)
                .unsubscribedAt(Instant.now())
                .campaignId(campaignId)
                .build());

        // Also update the organization's contact with this address, if any.
        recipientRepository.findByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(organizationId, normalized)
                .ifPresent(r -> {
                    r.setStatus(RecipientStatus.UNSUBSCRIBED);
                    recipientRepository.save(r);
                });

        log.info("Unsubscribed {} from organization {}", normalized, organizationId);
        return token;
    }

    @Transactional(readOnly = true)
    public UnsubscribeEntry findByToken(String token) {
        return unsubscribeRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Unsubscribe token not found"));
    }
}
