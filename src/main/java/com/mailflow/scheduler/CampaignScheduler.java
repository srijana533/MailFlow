package com.mailflow.scheduler;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.service.CampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls every 30 seconds for SCHEDULED campaigns whose {@code scheduledAt}
 * has passed and triggers their launch.
 *
 * <p>Each launch runs in its own transaction ({@link CampaignService#launch(Long)}
 * is {@code @Transactional}). This method deliberately has no transaction of its
 * own: with a shared transaction, one failing campaign marked it rollback-only
 * and rolled back every other launch in the same poll.
 *
 * <p>Idempotency: the campaign status is changed to QUEUED inside
 * {@link CampaignService#launch(Long)} which uses optimistic locking.
 * Concurrent scheduler instances (e.g. in a multi-pod deployment) will fail
 * the second update with an optimistic-locking exception and safely skip the
 * duplicate launch.
 *
 * <p>Deleted campaigns are never launched: the query leaves them out, and a campaign
 * deleted after the query is rejected by {@code launch} (not found, or the version
 * check fails when the delete commits first).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignScheduler {

    private final CampaignRepository campaignRepository;
    private final CampaignService campaignService;

    @Scheduled(fixedDelay = 30_000)
    public void triggerDueScheduledCampaigns() {
        List<Campaign> due = campaignRepository
                .findDueScheduledCampaigns(CampaignStatus.SCHEDULED, Instant.now());

        if (due.isEmpty()) return;

        log.info("Found {} scheduled campaign(s) due for launch", due.size());

        for (Campaign campaign : due) {
            try {
                campaignService.launch(campaign.getId());
                log.info("Auto-launched campaign id={} '{}'",
                        campaign.getId(), campaign.getName());
            } catch (Exception e) {
                log.error("Failed to auto-launch campaign {}: {}",
                        campaign.getId(), e.getMessage(), e);
            }
        }
    }
}
