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
import java.util.List;

/**
 * Updates campaign status when a batch job starts and finishes.
 *
 * <p>{@code @Transactional} is required on both methods because Spring Batch
 * job-level listeners do NOT run inside a step transaction — only step-level
 * callbacks share the step's transaction.
 *
 * <p>Every change is conditional on the status the job expects: a job starts a
 * QUEUED campaign, and only a RUNNING campaign becomes COMPLETED, FAILED or PAUSED
 * when its job ends. A campaign paused or cancelled while its job was running keeps
 * that status (the reader stops at its next page, see {@link CampaignBatchConfig}).
 * Cancelling also stops the execution ({@code JobOperator.stop}); its STOPPED end
 * changes only a RUNNING campaign, so CANCELLED is never turned into PAUSED.
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

        if (moveFrom(CampaignStatus.QUEUED, campaignId, CampaignStatus.RUNNING)) {
            log.info("Batch job starting — campaign {}", campaignId);
        } else {
            // For example cancelled while it waited in the queue: the reader finds
            // that it isn't RUNNING and reads nothing.
            log.warn("Batch job starting — campaign {} is no longer QUEUED; status unchanged", campaignId);
        }
    }

    @Override
    @Transactional
    public void afterJob(JobExecution jobExecution) {
        Long campaignId = jobExecution.getJobParameters().getLong("campaignId");
        if (campaignId == null) return;

        try {
            switch (jobExecution.getStatus()) {
                case COMPLETED -> {
                    if (moveFrom(CampaignStatus.RUNNING, campaignId, CampaignStatus.COMPLETED)) {
                        log.info("Batch job COMPLETED — campaign {}", campaignId);
                    } else {
                        log.info("Batch job COMPLETED — campaign {} was paused or cancelled meanwhile; status kept",
                                campaignId);
                    }
                }
                case FAILED -> {
                    moveFrom(CampaignStatus.RUNNING, campaignId, CampaignStatus.FAILED);
                    log.error("Batch job FAILED — campaign {}: {}",
                            campaignId, jobExecution.getAllFailureExceptions());
                }
                case STOPPED -> {
                    moveFrom(CampaignStatus.RUNNING, campaignId, CampaignStatus.PAUSED);
                    log.info("Batch job STOPPED — campaign {}", campaignId);
                }
                default -> log.warn("Batch job ended with status {} — campaign {}",
                        jobExecution.getStatus(), campaignId);
            }
        } finally {
            BatchJobContext.clear();
        }
    }

    /** Changes the status only while it is still {@code from}; true when it changed. */
    private boolean moveFrom(CampaignStatus from, Long campaignId, CampaignStatus to) {
        return campaignRepository.updateStatusIfIn(campaignId, to, List.of(from), Instant.now()) == 1;
    }
}
