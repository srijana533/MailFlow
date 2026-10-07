package com.mailflow.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Launches campaign batch jobs asynchronously so the REST controller
 * returns immediately without waiting for the entire campaign to finish.
 *
 * <p>We wire the Job bean with an explicit constructor (not Lombok) so that
 * {@code @Qualifier} is honoured.  Lombok's {@code @RequiredArgsConstructor}
 * generates a plain constructor that cannot carry qualifier metadata.
 */
@Slf4j
@Component
public class CampaignJobLauncher {

    private final JobLauncher jobLauncher;
    private final Job campaignEmailJob;

    @Autowired
    public CampaignJobLauncher(JobLauncher jobLauncher,
                               @Qualifier(CampaignBatchConfig.JOB_NAME) Job campaignEmailJob) {
        this.jobLauncher = jobLauncher;
        this.campaignEmailJob = campaignEmailJob;
    }

    @Async
    public void launchCampaignJob(Long campaignId) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("campaignId", campaignId)
                    .addLong("launchedAt", System.currentTimeMillis())
                    .toJobParameters();

            var execution = jobLauncher.run(campaignEmailJob, params);
            log.info("Launched batch job for campaign {}, executionId={}",
                     campaignId, execution.getId());
        } catch (Exception e) {
            log.error("Failed to launch batch job for campaign {}: {}",
                     campaignId, e.getMessage(), e);
        }
    }
}
