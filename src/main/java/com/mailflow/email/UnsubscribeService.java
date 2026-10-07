package com.mailflow.email;

import com.mailflow.common.exception.ResourceNotFoundException;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    private final UnsubscribeRepository unsubscribeRepository;
    private final RecipientRepository recipientRepository;

    @Cacheable(value = "unsubscribed", key = "#email.toLowerCase()")
    public boolean isUnsubscribed(String email) {
        return unsubscribeRepository.existsByEmailIgnoreCase(email);
    }

    @Transactional
    @CacheEvict(value = "unsubscribed", key = "#email.toLowerCase()")
    public String unsubscribe(String email, String reason, Long campaignId) {
        if (unsubscribeRepository.existsByEmailIgnoreCase(email)) {
            // Already unsubscribed — idempotent
            return unsubscribeRepository.findByEmailIgnoreCase(email)
                    .map(UnsubscribeEntry::getToken).orElse("");
        }

        String token = SlugUtils.generateToken();
        unsubscribeRepository.save(UnsubscribeEntry.builder()
                .email(email.toLowerCase())
                .token(token)
                .reason(reason)
                .unsubscribedAt(Instant.now())
                .campaignId(campaignId)
                .build());

        // Also update recipient status
        recipientRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).ifPresent(r -> {
            r.setStatus(RecipientStatus.UNSUBSCRIBED);
            recipientRepository.save(r);
        });

        log.info("Unsubscribed: {}", email);
        return token;
    }

    @Transactional(readOnly = true)
    public UnsubscribeEntry findByToken(String token) {
        return unsubscribeRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Unsubscribe token not found"));
    }
}
