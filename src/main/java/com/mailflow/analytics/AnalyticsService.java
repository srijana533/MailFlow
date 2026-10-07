package com.mailflow.analytics;

import com.mailflow.analytics.dto.CampaignAnalytics;
import com.mailflow.analytics.dto.DashboardSummary;
import com.mailflow.analytics.repository.EmailLogRepository;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.recipient.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final CampaignRepository campaignRepository;
    private final EmailLogRepository emailLogRepository;
    private final RecipientRepository recipientRepository;

    @Transactional(readOnly = true)
    public CampaignAnalytics getCampaignAnalytics(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", campaignId));

        // Aggregate from email_logs for accurate counts
        Map<String, Long> statusCounts = emailLogRepository
                .countByStatusForCampaign(campaignId)
                .stream()
                .collect(Collectors.toMap(
                    row -> (String) row[0],
                    row -> (Long) row[1]
                ));

        long sent    = statusCounts.getOrDefault("SENT", campaign.getSentCount());
        long failed  = statusCounts.getOrDefault("FAILED", campaign.getFailedCount());
        long skipped = campaign.getSkippedCount();
        long total   = campaign.getTotalRecipients();

        double successRate = total > 0 ? (double) sent / total * 100.0 : 0.0;
        double throughput  = calculateThroughput(campaign, sent);
        Long duration      = calculateDurationSeconds(campaign);

        return new CampaignAnalytics(
            campaignId, campaign.getName(),
            total, sent, failed, skipped,
            campaign.getBouncedCount(), campaign.getRetryCount(),
            successRate, throughput, duration
        );
    }

    @Transactional(readOnly = true)
    public DashboardSummary getDashboardSummary() {
        List<Campaign> all = campaignRepository.findAll();

        long totalCampaigns  = all.size();
        long activeCampaigns = all.stream()
                .filter(c -> c.getStatus() == CampaignStatus.RUNNING
                          || c.getStatus() == CampaignStatus.QUEUED)
                .count();

        long totalSent   = all.stream().mapToLong(Campaign::getSentCount).sum();
        long totalFailed = all.stream().mapToLong(Campaign::getFailedCount).sum();
        long totalEmails = totalSent + totalFailed;

        double successRate = totalEmails > 0
                ? (double) totalSent / totalEmails * 100.0 : 0.0;

        long totalRecipients = recipientRepository.countByDeletedAtIsNull();

        return new DashboardSummary(
            totalCampaigns, activeCampaigns,
            totalSent, totalFailed,
            successRate, totalRecipients
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDailyVolume(int days) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        Instant to   = Instant.now();
        return emailLogRepository.dailyVolume(from, to)
                .stream()
                .map(row -> Map.of("date", row[0].toString(), "count", row[1]))
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double calculateThroughput(Campaign campaign, long sent) {
        if (campaign.getStartedAt() == null || sent == 0) return 0.0;
        Instant end = campaign.getCompletedAt() != null ? campaign.getCompletedAt() : Instant.now();
        long seconds = Duration.between(campaign.getStartedAt(), end).getSeconds();
        if (seconds <= 0) return 0.0;
        return (double) sent / seconds * 60.0; // emails per minute
    }

    private Long calculateDurationSeconds(Campaign campaign) {
        if (campaign.getStartedAt() == null) return null;
        Instant end = campaign.getCompletedAt() != null ? campaign.getCompletedAt() : Instant.now();
        return Duration.between(campaign.getStartedAt(), end).getSeconds();
    }
}
