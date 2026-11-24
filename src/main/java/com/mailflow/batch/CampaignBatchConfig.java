package com.mailflow.batch;

import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.config.MailFlowProperties;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailMessage;
import com.mailflow.template.service.TemplateEngineService;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spring Batch configuration for the email campaign processing job.
 *
 * <p>All step-scoped beans ({@code @StepScope}) must be defined here (inside
 * a {@code @Configuration} class) so that Spring's full CGLIB proxy
 * interception applies.  Step-scoped beans defined in {@code @Component}
 * classes do NOT get the CGLIB proxy treatment and will not have their
 * constructor arguments late-bound from job parameters.
 *
 * <p>Pipeline (per campaign):
 * <pre>
 *   campaignItemReader  — paged JPA, reads PENDING/FAILED campaign_recipients
 *                         while the campaign is RUNNING
 *       ↓
 *   campaignItemProcessor — skips items once the campaign isn't RUNNING, checks
 *                           unsubscribe, personalises template variables
 *       ↓
 *   campaignItemWriter  — publishes EmailMessage to RabbitMQ, marks QUEUED
 *                         (nothing once the campaign isn't RUNNING)
 * </pre>
 * Each stage re-reads the status, so pausing, cancelling or deleting a campaign stops
 * it; cancelling also stops the job execution through {@code JobOperator.stop}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CampaignBatchConfig {

    public static final String JOB_NAME  = "campaignEmailJob";
    public static final String STEP_NAME = "processRecipientsStep";

    /**
     * Unprocessed delivery rows of one campaign. LEFT JOIN so rows without a saved
     * contact (campaign-only addresses, recipient NULL) are read too; rows of saved
     * contacts are read only while the contact is ACTIVE and not deleted.
     */
    static final String READER_QUERY = """
            SELECT cr FROM CampaignRecipient cr
            LEFT JOIN FETCH cr.recipient r
            WHERE cr.campaign.id = :campaignId
              AND cr.deliveryStatus IN ('PENDING', 'FAILED')
              AND (cr.recipient IS NULL
                   OR (r.deletedAt IS NULL AND r.status = 'ACTIVE'))
            ORDER BY cr.id
            """;

    // Singletons — safe to inject via constructor
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MailFlowProperties properties;
    private final EntityManagerFactory entityManagerFactory;
    private final CampaignJobListener jobListener;
    private final CampaignSkipListener skipListener;

    // Dependencies passed into step-scoped beans
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final TemplateEngineService templateEngine;
    private final UnsubscribeService unsubscribeService;
    private final RabbitTemplate rabbitTemplate;

    // Read before each page: the reader stops when the campaign is no longer RUNNING
    private final CampaignRepository campaignRepository;

    // ── Step-scoped beans ─────────────────────────────────────────────────────

    /**
     * Paged JPA reader scoped to one step execution.
     * The campaignId is resolved from job parameters at runtime via
     * {@code @Value("#{jobParameters['campaignId']}")}.
     */
    @Bean
    @StepScope
    public JpaPagingItemReader<CampaignRecipient> campaignItemReader(
            @Value("#{jobParameters['campaignId']}") Long campaignId) {

        // IMPORTANT: this reader's query matches only PENDING/FAILED rows, and
        // the writer flips each processed row to QUEUED. With a normal paging
        // reader the page offset would advance (0, 50, 100, …) while the
        // matching result set simultaneously shrinks as rows become QUEUED, so
        // after the first page every subsequent OFFSET would skip past all the
        // still-unprocessed rows and the job would stop after one chunk.
        //
        // Because each committed chunk removes its items from the result set,
        // the next unprocessed batch is always at offset 0. We therefore pin
        // the page number to 0 by overriding getPage(). saveState is disabled
        // since page-based restart state is meaningless under this scheme
        // (idempotency is still guaranteed: already-QUEUED/SENT rows no longer
        // match the query, and the processor skips SENT rows).
        return new JpaPagingItemReader<>() {
            {
                // Copy configuration from the builder-produced reader.
                setEntityManagerFactory(entityManagerFactory);
                setPageSize(properties.getBatch().getChunkSize());
                setQueryString(READER_QUERY);
                setParameterValues(Map.of("campaignId", campaignId));
                setSaveState(false);
                setName("campaignRecipientReader");
            }

            @Override
            public int getPage() {
                // Always re-read from the first page; processed rows have left
                // the result set, so offset 0 is always the next batch.
                return 0;
            }

            @Override
            protected void doReadPage() {
                // Pause, cancel and delete change the campaign row. Checking the status
                // before each page makes the job stop at the next chunk boundary: an
                // empty page ends the step, and the unread rows stay PENDING for a
                // resume. The job listener then keeps the PAUSED or CANCELLED status.
                // A deleted campaign has no status here (empty result).
                CampaignStatus status = campaignRepository.findStatusById(campaignId).orElse(null);
                if (status != CampaignStatus.RUNNING) {
                    log.info("Campaign {} is {}: no more recipients are read",
                            campaignId, status != null ? status : "deleted");
                    if (results == null) results = new CopyOnWriteArrayList<>();
                    else results.clear();
                    return;
                }
                super.doReadPage();
            }
        };
    }

    @Bean
    @StepScope
    public CampaignItemProcessor campaignItemProcessor() {
        return new CampaignItemProcessor(
                templateEngine, unsubscribeService, campaignRecipientRepository, campaignRepository);
    }

    @Bean
    @StepScope
    public CampaignItemWriter campaignItemWriter() {
        return new CampaignItemWriter(rabbitTemplate, campaignRecipientRepository, campaignRepository);
    }

    // ── Job / Step ────────────────────────────────────────────────────────────

    @Bean(name = JOB_NAME)
    public Job campaignEmailJob(
            JpaPagingItemReader<CampaignRecipient> campaignItemReader,
            CampaignItemProcessor campaignItemProcessor,
            CampaignItemWriter campaignItemWriter) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobListener)
                .start(processRecipientsStep(campaignItemReader, campaignItemProcessor, campaignItemWriter))
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step processRecipientsStep(
            JpaPagingItemReader<CampaignRecipient> campaignItemReader,
            CampaignItemProcessor campaignItemProcessor,
            CampaignItemWriter campaignItemWriter) {

        int chunkSize = properties.getBatch().getChunkSize();
        int skipLimit = properties.getBatch().getSkipLimit();

        return new StepBuilder(STEP_NAME, jobRepository)
                .<CampaignRecipient, EmailMessage>chunk(chunkSize, transactionManager)
                .reader(campaignItemReader)
                .processor(campaignItemProcessor)
                .writer(campaignItemWriter)
                .faultTolerant()
                    .skipLimit(skipLimit)
                    .skip(com.mailflow.common.exception.ValidationException.class)
                    .skip(IllegalArgumentException.class)
                    .noRollback(com.mailflow.common.exception.ValidationException.class)
                    .retryLimit(properties.getBatch().getMaxRetries())
                    .retry(org.springframework.mail.MailException.class)
                    .retry(java.net.ConnectException.class)
                .listener(skipListener)
                .throttleLimit(properties.getBatch().getThrottleLimit())
                .build();
    }
}
