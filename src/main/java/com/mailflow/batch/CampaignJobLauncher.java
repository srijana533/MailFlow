package com.mailflow.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Launches campaign batch jobs asynchronously so the REST controller
 * returns immediately without waiting for the entire campaign to finish.
 * It also finds and stops a campaign's running job executions, using the
 * {@code campaignId} job parameter recorded in Spring Batch's metadata.
 *
 * <p>We wire the Job bean with an explicit constructor (not Lombok) so that
 * {@code @Qualifier} is honoured.  Lombok's {@code @RequiredArgsConstructor}
 * generates a plain constructor that cannot carry qualifier metadata.
 */
@Slf4j
@Component
public class CampaignJobLauncher {

    /**
     * An execution that Spring Batch still records as running but that hasn't been
     * updated for this long is treated as abandoned (for example the server stopped
     * while it ran) and no longer counts as running. A campaign job publishes its
     * emails in seconds to minutes, and its steps update the metadata after every chunk.
     */
    public static final Duration STALE_AFTER = Duration.ofHours(24);

    private final JobLauncher jobLauncher;
    private final Job campaignEmailJob;
    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;

    @Autowired
    public CampaignJobLauncher(JobLauncher jobLauncher,
                               @Qualifier(CampaignBatchConfig.JOB_NAME) Job campaignEmailJob,
                               JobExplorer jobExplorer,
                               JobOperator jobOperator) {
        this.jobLauncher = jobLauncher;
        this.campaignEmailJob = campaignEmailJob;
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
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

    /**
     * This campaign's job executions that Spring Batch records as running (STARTING,
     * STARTED or STOPPING) and that were active within {@link #STALE_AFTER}, oldest
     * first. Matched on the {@code campaignId} job parameter.
     */
    public List<JobExecution> findRunningExecutions(Long campaignId) {
        LocalDateTime staleBefore = LocalDateTime.now().minus(STALE_AFTER);
        return jobExplorer.findRunningJobExecutions(CampaignBatchConfig.JOB_NAME).stream()
                .filter(JobExecution::isRunning)
                .filter(e -> campaignId.equals(e.getJobParameters().getLong("campaignId")))
                .filter(e -> {
                    LocalDateTime last = lastActivity(e);
                    return last == null || !last.isBefore(staleBefore);
                })
                .sorted(Comparator.comparing(JobExecution::getId))
                .toList();
    }

    /**
     * Asks Spring Batch to stop this campaign's running job executions
     * ({@link JobOperator#stop}): each one ends at its next chunk boundary with status
     * STOPPED, and the job listener keeps the campaign's CANCELLED status.
     *
     * <p>Asynchronous like the launch: it is called after the cancel has committed,
     * and running on another thread keeps Spring Batch's metadata updates out of that
     * finished transaction. Failures are only logged; the campaign status already
     * stops the reader, processor, writer and consumer.
     */
    @Async
    public void stopRunningJobs(Long campaignId) {
        try {
            for (JobExecution execution : findRunningExecutions(campaignId)) {
                try {
                    jobOperator.stop(execution.getId());
                    log.info("Stop requested for batch job execution {} of campaign {}",
                            execution.getId(), campaignId);
                } catch (JobExecutionNotRunningException | NoSuchJobExecutionException e) {
                    log.debug("Batch job execution {} of campaign {} is no longer running",
                            execution.getId(), campaignId);
                }
            }
        } catch (Exception e) {
            log.warn("Could not stop the batch job of campaign {}: {}", campaignId, e.getMessage(), e);
        }
    }

    /**
     * The latest update recorded for the execution or any of its steps (steps are
     * updated after every chunk); null when none is recorded.
     */
    static LocalDateTime lastActivity(JobExecution e) {
        Stream<LocalDateTime> steps = e.getStepExecutions().stream().map(StepExecution::getLastUpdated);
        return Stream.concat(Stream.of(e.getLastUpdated(), e.getStartTime(), e.getCreateTime()), steps)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
