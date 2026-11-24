package com.mailflow.email;

import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.common.util.SlugUtils;
import com.mailflow.email.entity.UnsubscribeEntry;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.recipient.entity.Recipient;
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
 * The unsubscribe list. Addresses are normalised ({@link EmailValidator#normalize})
 * before they are stored, looked up or used as a cache key, so an unsubscribe made
 * with different case or surrounding whitespace still matches the address used
 * when sending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    private final UnsubscribeRepository unsubscribeRepository;
    private final RecipientRepository recipientRepository;

    @Cacheable(value = "unsubscribed", key = "T(com.mailflow.common.util.EmailValidator).normalize(#email)")
    public boolean isUnsubscribed(String email) {
        return unsubscribeRepository.existsByEmailIgnoreCase(EmailValidator.normalize(email));
    }

    @Transactional
    @CacheEvict(value = "unsubscribed", key = "T(com.mailflow.common.util.EmailValidator).normalize(#email)")
    public String unsubscribe(String email, String reason, Long campaignId) {
        String normalized = EmailValidator.normalize(email);
        if (unsubscribeRepository.existsByEmailIgnoreCase(normalized)) {
            // Already unsubscribed — idempotent
            return unsubscribeRepository.findByEmailIgnoreCase(normalized)
                    .map(UnsubscribeEntry::getToken).orElse("");
        }

        String token = SlugUtils.generateToken();
        unsubscribeRepository.save(UnsubscribeEntry.builder()
                .email(normalized)
                .token(token)
                .reason(reason)
                .unsubscribedAt(Instant.now())
                .campaignId(campaignId)
                .build());

        // Also update recipient status
        recipientRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(normalized).ifPresent(r -> {
            r.setStatus(RecipientStatus.UNSUBSCRIBED);
            recipientRepository.save(r);
        });

        log.info("Unsubscribed: {}", normalized);
        return token;
    }

    @Transactional(readOnly = true)
    public UnsubscribeEntry findByToken(String token) {
        return unsubscribeRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Unsubscribe token not found"));
    }
}
