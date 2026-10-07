package com.mailflow.batch;

import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.queue.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

/**
 * Logs items that were skipped by the fault-tolerant step.
 * Also increments the skip counter — analytics picks this up later.
 */
@Slf4j
@Component
public class CampaignSkipListener implements SkipListener<CampaignRecipient, EmailMessage> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("Skipped during read: {}", t.getMessage());
    }

    @Override
    public void onSkipInProcess(CampaignRecipient item, Throwable t) {
        log.warn("Skipped during process for recipient {}: {}",
                 item.getRecipient().getEmail(), t.getMessage());
    }

    @Override
    public void onSkipInWrite(EmailMessage item, Throwable t) {
        log.warn("Skipped during write for {}: {}", item.toAddress(), t.getMessage());
    }
}
