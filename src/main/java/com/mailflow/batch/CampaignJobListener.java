package com.mailflow.batch;

import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Updates campaign status when a batch job starts and finishes.
 *
 * <p>{@code @Transactional} is required on both methods because Spring Batch
 * job-level listeners do NOT run inside a step transaction — only step-level
 * callbacks share the step's transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignJobListener implements JobExecutionListener {

    private final CampaignRepository campaignRepository;

    @Override
    @Transactional
    public void beforeJob(JobExecution jobExecution) {
        Long campaignId = jobExecution.getJobParameters().getLong("campaignId");
        if (campaignId == null) return;

        campaignRepository.updateStatus(campaignId, CampaignStatus.RUNNING, Instant.now());
        log.info("Batch job starting — campaign {}", campaignId);
    }

    @Override
    @Transactional
    public void afterJob(JobExecution jobExecution) {
        Long campaignId = jobExecution.getJobParameters().getLong("campaignId");
        if (campaignId == null) return;

        try {
            switch (jobExecution.getStatus()) {
                case COMPLETED -> {
                    campaignRepository.updateStatus(
                            campaignId, CampaignStatus.COMPLETED, Instant.now());
                    log.info("Batch job COMPLETED — campaign {}", campaignId);
                }
                case FAILED -> {
                    campaignRepository.updateStatus(
                            campaignId, CampaignStatus.FAILED, Instant.now());
                    log.error("Batch job FAILED — campaign {}: {}",
                            campaignId, jobExecution.getAllFailureExceptions());
                }
                case STOPPED -> {
                    campaignRepository.updateStatus(
                            campaignId, CampaignStatus.PAUSED, Instant.now());
                    log.info("Batch job STOPPED (paused) — campaign {}", campaignId);
                }
                default -> log.warn("Batch job ended with status {} — campaign {}",
                        jobExecution.getStatus(), campaignId);
            }
        } finally {
            BatchJobContext.clear();
        }
    }
}
