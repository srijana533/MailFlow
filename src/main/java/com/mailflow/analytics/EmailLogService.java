package com.mailflow.analytics;

import com.mailflow.analytics.entity.EmailLog;
import com.mailflow.analytics.repository.EmailLogRepository;
import com.mailflow.queue.EmailMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists email delivery events.
 * Async so it doesn't block the consumer's main processing path.
 */
@Service
@RequiredArgsConstructor
public class EmailLogService {

    private final EmailLogRepository emailLogRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSuccess(EmailMessage message) {
        emailLogRepository.save(EmailLog.builder()
                .campaignId(message.campaignId())
                .recipientId(message.recipientId())
                .toAddress(message.toAddress())
                .subject(message.subject())
                .status("SENT")
                .attemptNumber(message.attemptNumber())
                .processedAt(Instant.now())
                .build());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(EmailMessage message, String errorMessage, String errorType) {
        emailLogRepository.save(EmailLog.builder()
                .campaignId(message.campaignId())
                .recipientId(message.recipientId())
                .toAddress(message.toAddress())
                .subject(message.subject())
                .status("FAILED")
                .errorMessage(errorMessage)
                .errorType(errorType)
                .attemptNumber(message.attemptNumber())
                .processedAt(Instant.now())
                .build());
    }
}
