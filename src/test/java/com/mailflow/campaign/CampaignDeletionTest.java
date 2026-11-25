package com.mailflow.campaign;

import com.mailflow.analytics.AnalyticsService;
import com.mailflow.analytics.EmailLogService;
import com.mailflow.analytics.dto.DashboardSummary;
import com.mailflow.analytics.entity.EmailLog;
import com.mailflow.analytics.repository.EmailLogRepository;
import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.batch.CampaignBatchConfig;
import com.mailflow.batch.CampaignItemProcessor;
import com.mailflow.batch.CampaignItemWriter;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.batch.CampaignJobListener;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.MailFlowException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.email.EmailProvider;
import com.mailflow.email.RateLimiterService;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.email.entity.UnsubscribeEntry;
import com.mailflow.queue.EmailConsumer;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientGroup;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.scheduler.CampaignScheduler;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateEngineService;
import com.mailflow.template.service.TemplateService;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.item.Chunk;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Feature 2, safe campaign deletion: one named test per acceptance item (1-10, see
 * the step report for the mapping), plus supporting checks.
 *
 * <p>The real {@link CampaignService}, {@link AnalyticsService}, {@link CampaignScheduler},
 * {@link CampaignJobLauncher} and the batch processor, writer, listener and email
 * consumer run against real Spring Data JPA repositories on an in-memory H2 database
 * with hand-written tables (the V1, V4, V5 and V6 columns and foreign keys that matter
 * here), in real transactions ({@link JpaTransactionManager}), so the JPQL, the row
 * lock and {@code @Version} are exercised. Mocks: Spring Batch's JobLauncher,
 * JobExplorer and JobOperator, the template service, the unsubscribe check, RabbitMQ,
 * SMTP and Redis. PostgreSQL itself is not used.
 */
class CampaignDeletionTest {

    @org.junit.jupiter.api.BeforeEach
    void signInTestOrganization() {
        com.mailflow.tenant.TestTenant.signIn();
    }

    @org.junit.jupiter.api.AfterEach
    void signOutTestOrganization() {
        com.mailflow.tenant.TestTenant.signOut();
    }

    private static final String URL = "jdbc:h2:mem:campaign_deletion;DB_CLOSE_DELAY=-1";
    private static final long MANAGER_ID = 42L;

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;
    private static JpaTransactionManager txManager;
    private static TransactionTemplate tx;

    private static CampaignRepository campaignRepository;
    private static CampaignRecipientRepository deliveryRows;
    private static CampaignTargetAddressRepository targetAddresses;
    private static RecipientRepository recipientRepository;
    private static EmailLogRepository emailLogRepository;

    private JobLauncher batchLauncher;
    private JobExplorer jobExplorer;
    private JobOperator jobOperator;
    private CampaignJobLauncher jobControl;
    private UnsubscribeService unsubscribeService;
    private CampaignService service;
    private AnalyticsService analytics;

    @BeforeAll
    static void createDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE campaigns (organization_id BIGINT DEFAULT 1, 
                        id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, description VARCHAR(1000),
                        subject VARCHAR(1000) NOT NULL, template_id BIGINT, sender_name VARCHAR(255),
                        sender_email VARCHAR(255), reply_to VARCHAR(255), status VARCHAR(50) NOT NULL,
                        scheduled_at TIMESTAMP WITH TIME ZONE, created_by BIGINT,
                        created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE,
                        started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE,
                        total_recipients BIGINT NOT NULL, sent_count BIGINT NOT NULL,
                        failed_count BIGINT NOT NULL, skipped_count BIGINT NOT NULL,
                        bounced_count BIGINT NOT NULL, retry_count BIGINT NOT NULL,
                        batch_job_id VARCHAR(255), version BIGINT NOT NULL,
                        deleted_at TIMESTAMP WITH TIME ZONE, deleted_by BIGINT,
                        custom_body VARCHAR(100000))""");
            s.execute("""
                    CREATE TABLE email_templates (organization_id BIGINT DEFAULT 1, 
                        id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, description VARCHAR(1000),
                        subject VARCHAR(1000) NOT NULL, html_body VARCHAR(10000), plain_body VARCHAR(10000),
                        variables JSON, created_by BIGINT, created_at TIMESTAMP WITH TIME ZONE,
                        updated_at TIMESTAMP WITH TIME ZONE, deleted_at TIMESTAMP WITH TIME ZONE,
                        version BIGINT NOT NULL)""");
            s.execute("""
                    CREATE TABLE recipients (organization_id BIGINT DEFAULT 1, 
                        id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL UNIQUE, name VARCHAR(255),
                        first_name VARCHAR(100), last_name VARCHAR(100), company VARCHAR(255),
                        course VARCHAR(255), custom_fields JSON, status VARCHAR(50) NOT NULL,
                        import_source VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE,
                        updated_at TIMESTAMP WITH TIME ZONE, deleted_at TIMESTAMP WITH TIME ZONE,
                        version BIGINT NOT NULL)""");
            s.execute("""
                    CREATE TABLE recipient_groups (organization_id BIGINT DEFAULT 1, 
                        id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, description VARCHAR(1000),
                        created_by BIGINT, created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)""");
            s.execute("""
                    CREATE TABLE recipient_group_members (
                        recipient_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                        added_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (recipient_id, group_id))""");
            // V4 (CASCADE)
            s.execute("""
                    CREATE TABLE campaign_target_groups (
                        campaign_id BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                        group_id BIGINT NOT NULL, PRIMARY KEY (campaign_id, group_id))""");
            s.execute("""
                    CREATE TABLE campaign_target_recipients (
                        campaign_id BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                        recipient_id BIGINT NOT NULL, PRIMARY KEY (campaign_id, recipient_id))""");
            // V5 (CASCADE)
            s.execute("""
                    CREATE TABLE campaign_target_addresses (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        campaign_id BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                        email VARCHAR(255) NOT NULL, name VARCHAR(255), first_name VARCHAR(100),
                        last_name VARCHAR(100), company VARCHAR(255), custom_fields JSON,
                        created_at TIMESTAMP WITH TIME ZONE, UNIQUE (campaign_id, email))""");
            // V1 + V5 (CASCADE)
            s.execute("""
                    CREATE TABLE campaign_recipients (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        campaign_id BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                        recipient_id BIGINT, delivery_status VARCHAR(50) NOT NULL,
                        idempotency_key VARCHAR(100) NOT NULL UNIQUE,
                        queued_at TIMESTAMP WITH TIME ZONE, sent_at TIMESTAMP WITH TIME ZONE,
                        failed_at TIMESTAMP WITH TIME ZONE, retry_count INT NOT NULL,
                        last_error VARCHAR(1000), personalized_subject VARCHAR(1000),
                        email VARCHAR(255), name VARCHAR(255), first_name VARCHAR(100),
                        last_name VARCHAR(100), company VARCHAR(255), custom_fields JSON)""");
            // V1 (NO ACTION): these keep a hard delete from removing a campaign with history.
            s.execute("""
                    CREATE TABLE email_logs (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        campaign_id BIGINT NOT NULL REFERENCES campaigns(id),
                        recipient_id BIGINT, campaign_recipient_id BIGINT REFERENCES campaign_recipients(id),
                        to_address VARCHAR(255) NOT NULL, subject VARCHAR(1000), status VARCHAR(50) NOT NULL,
                        provider_message_id VARCHAR(500), error_message VARCHAR(1000), error_type VARCHAR(100),
                        attempt_number INT NOT NULL, processed_at TIMESTAMP WITH TIME ZONE NOT NULL)""");
            s.execute("""
                    CREATE TABLE unsubscribe_list (organization_id BIGINT DEFAULT 1, 
                        id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL UNIQUE, token VARCHAR(64) NOT NULL,
                        reason VARCHAR(1000), unsubscribed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        campaign_id BIGINT REFERENCES campaigns(id))""");
        }

        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.tenant_identifier_resolver", new com.mailflow.tenant.TenantIdentifierResolver())
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClasses(Campaign.class, CampaignRecipient.class, CampaignTargetAddress.class,
                        Recipient.class, RecipientGroup.class, EmailTemplate.class, EmailLog.class,
                        UnsubscribeEntry.class, User.class, Role.class, com.mailflow.organization.entity.Organization.class)
                .buildMetadata()
                .buildSessionFactory();

        txManager = new JpaTransactionManager(sessionFactory);
        txManager.setJpaDialect(new HibernateJpaDialect()); // what Spring Boot configures
        txManager.afterPropertiesSet();
        tx = new TransactionTemplate(txManager);

        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(sessionFactory);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        campaignRepository = repositories.getRepository(CampaignRepository.class);
        deliveryRows = repositories.getRepository(CampaignRecipientRepository.class);
        targetAddresses = repositories.getRepository(CampaignTargetAddressRepository.class);
        recipientRepository = repositories.getRepository(RecipientRepository.class);
        emailLogRepository = repositories.getRepository(EmailLogRepository.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @BeforeEach
    void createServices() throws Exception {
        batchLauncher = mock(JobLauncher.class);
        when(batchLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(new JobExecution(1L));
        jobExplorer = mock(JobExplorer.class); // no running executions unless a test adds some
        jobOperator = mock(JobOperator.class);
        jobControl = new CampaignJobLauncher(batchLauncher, mock(Job.class), jobExplorer, jobOperator);
        unsubscribeService = mock(UnsubscribeService.class);

        service = transactional(new CampaignService(campaignRepository, mock(TemplateService.class), jobControl,
                recipientRepository, deliveryRows, unsubscribeService, targetAddresses));
        analytics = transactional(new AnalyticsService(campaignRepository, emailLogRepository, recipientRepository));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── 1. Soft delete ──────────────────────────────────────────────────────────

    @Test
    void item01_delete_is_a_soft_delete_that_keeps_delivery_history_and_removes_the_campaign_only_address_list()
            throws Exception {
        long sent = 101, draft = 102;
        insertCampaign(sent, "Autumn newsletter", CampaignStatus.COMPLETED, null, 2, 0, null);
        insertContact(10_101, "item01-ada@test.local");
        insertTargetRecipient(sent, 10_101);
        insertTargetAddress(sent, "item01-bob@test.local");
        long contactRow = insertDeliveryRow(30_101, sent, 10_101L, "SENT", "campaign_101_recipient_10101", null, null);
        long addressRow = insertDeliveryRow(30_102, sent, null, "SENT", "campaign_101_addr_bob", null,
                "item01-bob@test.local");
        insertEmailLog(sent, contactRow, "item01-ada@test.local", "SENT", Instant.now());
        insertEmailLog(sent, addressRow, "item01-bob@test.local", "SENT", Instant.now());
        insertUnsubscribe(10_101, "item01-carol@test.local", sent);

        insertCampaign(draft, "Never sent", CampaignStatus.DRAFT, null, 0, 0, null);
        insertTargetRecipient(draft, 10_101);
        insertTargetAddress(draft, "item01-dan@test.local");
        insertTargetAddress(draft, "item01-eve@test.local");

        // Why not a hard delete: the NO ACTION keys refuse it for a campaign with history.
        assertThatThrownBy(() -> exec("DELETE FROM campaigns WHERE id = ?", sent)).isInstanceOf(SQLException.class);

        signInAs(MANAGER_ID);
        Instant before = Instant.now().minusSeconds(1);
        service.delete(sent);
        service.delete(draft);

        for (long id : List.of(sent, draft)) {
            Map<String, Object> row = row("SELECT deleted_at, deleted_by, version, status FROM campaigns WHERE id = ?", id);
            assertThat(row).as("the row is kept").isNotNull();
            assertThat(((OffsetDateTime) row.get("DELETED_AT")).toInstant()).isAfter(before);
            assertThat(row.get("DELETED_BY")).isEqualTo(MANAGER_ID);
            assertThat(row.get("VERSION")).isEqualTo(1L);
        }
        assertThat(value("SELECT status FROM campaigns WHERE id = ?", sent)).isEqualTo("COMPLETED");
        assertThat(value("SELECT status FROM campaigns WHERE id = ?", draft)).isEqualTo("DRAFT");

        // Delivery history is kept: delivery rows (with the address snapshot), logs, unsubscribe entries.
        assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ?", sent)).isEqualTo(2);
        assertThat(value("SELECT email FROM campaign_recipients WHERE id = ?", addressRow))
                .isEqualTo("item01-bob@test.local");
        assertThat(count("SELECT COUNT(*) FROM email_logs WHERE campaign_id = ?", sent)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM unsubscribe_list WHERE campaign_id = ?", sent)).isEqualTo(1);
        // The campaign-only address list is deleted (sent campaign and never-sent draft alike) ...
        assertThat(count("SELECT COUNT(*) FROM campaign_target_addresses WHERE campaign_id IN (?, ?)", sent, draft))
                .isZero();
        // ... while the saved-contact selection (IDs only) stays with the hidden row.
        assertThat(count("SELECT COUNT(*) FROM campaign_target_recipients WHERE campaign_id IN (?, ?)", sent, draft))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM recipients WHERE id = 10101 AND deleted_at IS NULL")).isOne();
    }

    // ── 2. Gone from lists, search, GET and analytics ───────────────────────────

    @Test
    void item02_a_deleted_campaign_is_gone_from_lists_and_search_and_get_and_analytics_return_404() throws Exception {
        long gone = 201, kept = 202;
        insertCampaign(gone, "Item02 spring promo", CampaignStatus.COMPLETED, null, 3, 0, null);
        insertCampaign(kept, "Item02 spring sale", CampaignStatus.COMPLETED, null, 1, 0, null);
        PageRequest page = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(ids(service.findAll(null, "item02 spring", page).content())).containsExactlyInAnyOrder(gone, kept);

        service.delete(gone);

        assertThat(ids(service.findAll(null, "item02 spring", page).content())).containsExactly(kept);
        assertThat(ids(service.findAll(CampaignStatus.COMPLETED, null, page).content()))
                .contains(kept).doesNotContain(gone);
        assertThat(ids(service.findAll(null, null, page).content())).contains(kept).doesNotContain(gone);
        assertThat(service.findAll(null, "item02 spring", page).totalElements()).isOne();

        assertNotFound(() -> service.findById(gone));
        assertNotFound(() -> analytics.getCampaignAnalytics(gone));
        assertThat(service.findById(kept).id()).isEqualTo(kept);
        assertThat(analytics.getCampaignAnalytics(kept).campaignId()).isEqualTo(kept);
    }

    // ── 3. Dashboard and analytics totals ───────────────────────────────────────

    @Test
    void item03_dashboard_totals_and_daily_volume_exclude_deleted_campaigns() throws Exception {
        long gone = 301, kept = 302;
        insertCampaign(gone, "Item03 big send", CampaignStatus.COMPLETED, null, 5, 1, null);
        insertCampaign(kept, "Item03 small send", CampaignStatus.COMPLETED, null, 2, 0, null);
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) insertEmailLog(gone, null, "item03-" + i + "@test.local", "SENT", twoDaysAgo);
        insertEmailLog(gone, null, "item03-f@test.local", "FAILED", twoDaysAgo);
        for (int i = 0; i < 2; i++) insertEmailLog(kept, null, "item03-k" + i + "@test.local", "SENT", twoDaysAgo);

        DashboardSummary before = analytics.getDashboardSummary();
        long volumeBefore = totalVolume(analytics.getDailyVolume(30));

        service.delete(gone);

        DashboardSummary after = analytics.getDashboardSummary();
        assertThat(before.totalCampaigns() - after.totalCampaigns()).isOne();
        assertThat(before.totalEmailsSent() - after.totalEmailsSent()).isEqualTo(5);
        assertThat(before.totalEmailsFailed() - after.totalEmailsFailed()).isOne();
        assertThat(after.totalRecipients()).isEqualTo(before.totalRecipients()); // contacts are not campaigns
        assertThat(volumeBefore - totalVolume(analytics.getDailyVolume(30))).isEqualTo(5);
        // The log rows themselves are kept.
        assertThat(count("SELECT COUNT(*) FROM email_logs WHERE campaign_id = ?", gone)).isEqualTo(6);
    }

    // ── 4. Scheduler ────────────────────────────────────────────────────────────

    @Test
    void item04_the_scheduler_never_launches_a_deleted_campaign() throws Exception {
        long due = 401, deleted = 402;
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        insertCampaign(due, "Item04 due", CampaignStatus.SCHEDULED, null, 0, 0, past);
        insertCampaign(deleted, "Item04 deleted", CampaignStatus.SCHEDULED, null, 0, 0, past);
        service.delete(deleted);

        assertThat(ids(campaignRepository.findDueScheduledCampaigns(CampaignStatus.SCHEDULED, Instant.now())))
                .contains(due).doesNotContain(deleted);

        CampaignService launcher = mock(CampaignService.class);
        new CampaignScheduler(campaignRepository, launcher).triggerDueScheduledCampaigns();
        verify(launcher).launch(due);
        verify(launcher, never()).launch(deleted);

        // Even when called directly (a scheduler run that loaded it before the delete), launch refuses it.
        assertNotFound(() -> service.launch(deleted));
        assertThat(value("SELECT status FROM campaigns WHERE id = ?", deleted)).isEqualTo("SCHEDULED");
        verifyNoInteractions(batchLauncher);
    }

    // ── 5. Every action rejects a deleted campaign ──────────────────────────────

    @Test
    void item05_launch_resume_pause_cancel_update_and_delete_reject_a_deleted_campaign_with_404() throws Exception {
        long draft = 501, paused = 502;
        insertTemplate(20_501);
        insertCampaign(draft, "Item05 draft", CampaignStatus.DRAFT, 20_501L, 0, 0, null);
        insertContact(10_501, "item05-ada@test.local");
        insertTargetRecipient(draft, 10_501);
        insertCampaign(paused, "Item05 paused", CampaignStatus.PAUSED, 20_501L, 1, 0, null);
        service.delete(draft);
        service.delete(paused);
        Map<String, Object> draftBefore = row("SELECT * FROM campaigns WHERE id = ?", draft);
        Map<String, Object> pausedBefore = row("SELECT * FROM campaigns WHERE id = ?", paused);

        CampaignRequest edit = new CampaignRequest("Renamed", null, "Hi", null, null,
                "sender@test.local", null, null, null, null, null, null);
        assertNotFound(() -> service.findById(draft));
        assertNotFound(() -> service.update(draft, edit));
        assertNotFound(() -> service.launch(draft));
        assertNotFound(() -> service.cancel(draft));
        assertNotFound(() -> service.resume(paused));
        assertNotFound(() -> service.pause(paused));
        assertNotFound(() -> service.launch(paused));
        assertNotFound(() -> service.cancel(paused));
        assertNotFound(() -> service.delete(draft)); // a second delete
        assertNotFound(() -> analytics.getCampaignAnalytics(paused));

        assertThat(row("SELECT * FROM campaigns WHERE id = ?", draft)).isEqualTo(draftBefore);
        assertThat(row("SELECT * FROM campaigns WHERE id = ?", paused)).isEqualTo(pausedBefore);
        assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id IN (?, ?)", draft, paused)).isZero();
        verifyNoInteractions(batchLauncher, jobOperator);
    }

    // ── 6. Every status ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(CampaignStatus.class)
    void item06_deletion_rule_for_every_status(CampaignStatus status) throws Exception {
        long id = 600 + status.ordinal();
        insertCampaign(id, "Item06 " + status, status, null, 0, 0, null);
        insertTargetAddress(id, "item06-" + status.name().toLowerCase() + "@test.local");

        if (status == CampaignStatus.QUEUED || status == CampaignStatus.RUNNING) {
            assertThatThrownBy(() -> service.delete(id))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This campaign is sending. Cancel it, wait until sending stops, then delete it.")
                    .satisfies(e -> assertThat(((MailFlowException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", id)).isNull();
            assertThat(count("SELECT COUNT(*) FROM campaign_target_addresses WHERE campaign_id = ?", id)).isOne();
        } else {
            service.delete(id);
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", id)).isNotNull();
            assertThat(value("SELECT status FROM campaigns WHERE id = ?", id)).isEqualTo(status.name());
            assertThat(count("SELECT COUNT(*) FROM campaign_target_addresses WHERE campaign_id = ?", id)).isZero();
        }
    }

    // ── 7. Running job or queued emails: 409 with what is running; bounded rule ──

    @Test
    void item07_a_running_job_or_queued_emails_give_409_with_what_is_running_and_stale_ones_no_longer_block()
            throws Exception {
        long withJob = 701, withQueued = 702, pausedQueued = 703, stale = 704;
        insertCampaign(withJob, "Item07 job", CampaignStatus.CANCELLED, null, 0, 0, null);
        insertCampaign(withQueued, "Item07 queued", CampaignStatus.COMPLETED, null, 1, 0, null);
        insertCampaign(pausedQueued, "Item07 paused", CampaignStatus.PAUSED, null, 0, 0, null);
        insertCampaign(stale, "Item07 stale", CampaignStatus.COMPLETED, null, 0, 0, null);

        // A running job execution of this campaign (matched on the campaignId parameter).
        LocalDateTime now = LocalDateTime.now();
        JobExecution running = execution(7001, withJob, now.minusMinutes(1));
        when(jobExplorer.findRunningJobExecutions(CampaignBatchConfig.JOB_NAME)).thenReturn(Set.of(running));
        assertThatThrownBy(() -> service.delete(withJob))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("its send job is still running (started ")
                .hasMessageContaining("Wait until it has stopped, then delete the campaign");
        // The same execution without progress for 24 hours no longer blocks (server restarted).
        running.setLastUpdated(now.minusHours(25));
        running.setStartTime(now.minusHours(25));
        running.setCreateTime(now.minusHours(25));
        service.delete(withJob);
        assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", withJob)).isNotNull();

        // Emails still waiting in RabbitMQ (QUEUED rows queued within the queue's 24-hour TTL).
        Instant queuedAt = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        insertDeliveryRow(30_701, withQueued, null, "QUEUED", "campaign_702_addr_a", queuedAt, "item07-a@test.local");
        insertDeliveryRow(30_702, withQueued, null, "SENT", "campaign_702_addr_b", queuedAt, "item07-b@test.local");
        String deletableFrom = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
                .withZone(ZoneOffset.UTC).format(queuedAt.plus(Duration.ofHours(24)));
        assertThatThrownBy(() -> service.delete(withQueued))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This campaign can't be deleted yet: 1 of its emails is still waiting in the send queue. "
                        + "Wait until they have been sent or skipped, then delete the campaign. Emails queued more "
                        + "than 24 hours ago no longer block deletion, so it can be deleted from " + deletableFrom
                        + " at the latest.");
        insertDeliveryRow(30_703, pausedQueued, null, "QUEUED", "campaign_703_addr_a", queuedAt, "item07-c@test.local");
        insertDeliveryRow(30_704, pausedQueued, null, "QUEUED", "campaign_703_addr_b", queuedAt, "item07-d@test.local");
        assertThatThrownBy(() -> service.delete(pausedQueued))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2 of its emails are still waiting in the send queue");
        assertThat(count("SELECT COUNT(*) FROM campaigns WHERE id IN (?, ?) AND deleted_at IS NULL",
                withQueued, pausedQueued)).isEqualTo(2);

        // Bounded rule: rows QUEUED more than 24 hours ago (their message has expired from
        // email.queue or was dead-lettered), or without a queue time, don't block.
        insertDeliveryRow(30_705, stale, null, "QUEUED", "campaign_704_addr_a",
                Instant.now().minus(25, ChronoUnit.HOURS), "item07-e@test.local");
        insertDeliveryRow(30_706, stale, null, "QUEUED", "campaign_704_addr_b", null, "item07-f@test.local");
        service.delete(stale);
        assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", stale)).isNotNull();
        // The stuck rows themselves are left as they are.
        assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ? AND delivery_status = 'QUEUED'",
                stale)).isEqualTo(2);
    }

    // ── 8. Atomic against launch and resume ─────────────────────────────────────

    @Test
    void item08_delete_is_atomic_against_launch_and_resume() throws Exception {
        long raced = 801, racedResume = 802, launchedFirst = 803, deletedFirst = 804, locked = 805;
        insertTemplate(20_801);
        insertContact(10_801, "item08-ada@test.local");
        for (long id : List.of(raced, launchedFirst, deletedFirst, locked)) {
            insertCampaign(id, "Item08 " + id, CampaignStatus.DRAFT, 20_801L, 0, 0, null);
            insertTargetRecipient(id, 10_801);
        }
        insertCampaign(racedResume, "Item08 paused", CampaignStatus.PAUSED, 20_801L, 0, 0, null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // (a) The launch loaded the campaign, then the delete committed: the launch's
            // version check fails, everything it wrote is rolled back and no job starts.
            Throwable launchFailure = raceAgainstDelete(pool, raced, () -> service.launch(raced));
            assertThat(launchFailure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", raced)).isNotNull();
            assertThat(value("SELECT status FROM campaigns WHERE id = ?", raced)).isEqualTo("DRAFT");
            assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ?", raced)).isZero();

            // (b) The same for a resume of a paused campaign.
            Throwable resumeFailure = raceAgainstDelete(pool, racedResume, () -> service.resume(racedResume));
            assertThat(resumeFailure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(value("SELECT status FROM campaigns WHERE id = ?", racedResume)).isEqualTo("PAUSED");
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", racedResume)).isNotNull();
            verifyNoInteractions(batchLauncher);

            // (c) The launch committed first: the delete sees a sending campaign and refuses.
            service.launch(launchedFirst);
            assertThatThrownBy(() -> service.delete(launchedFirst))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CampaignService.DELETE_WHILE_SENDING);
            assertThat(value("SELECT status FROM campaigns WHERE id = ?", launchedFirst)).isEqualTo("QUEUED");
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", launchedFirst)).isNull();
            verify(batchLauncher, times(1)).run(any(Job.class), argThat(p -> p.getLong("campaignId") == launchedFirst));

            // (d) The delete committed first: the launch finds no campaign.
            service.delete(deletedFirst);
            assertNotFound(() -> service.launch(deletedFirst));
            assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ?", deletedFirst)).isZero();

            // (e) While the delete runs its checks it holds the campaign row lock, so a
            // concurrent status change (a launch committing) has to wait for it.
            CountDownLatch checking = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(jobExplorer.findRunningJobExecutions(CampaignBatchConfig.JOB_NAME)).thenAnswer(inv -> {
                checking.countDown();
                await(release);
                return Set.of();
            });
            Future<?> deleting = pool.submit(() -> service.delete(locked));
            await(checking);
            try (Connection c = DriverManager.getConnection(URL, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("SET LOCK_TIMEOUT 300");
                assertThatThrownBy(() -> st.executeUpdate(
                        "UPDATE campaigns SET status = 'QUEUED', version = version + 1 WHERE id = " + locked))
                        .isInstanceOf(SQLException.class);
            } finally {
                release.countDown();
            }
            deleting.get(20, TimeUnit.SECONDS);
            assertThat(value("SELECT status FROM campaigns WHERE id = ?", locked)).isEqualTo("DRAFT");
            assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", locked)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 9. Cancel stops sending; a cancelled or deleted campaign sends nothing more ──

    @Test
    void item09_cancel_stops_sending_and_a_cancelled_or_deleted_campaign_sends_nothing_more() throws Exception {
        long cancelled = 901, deleted = 902, paused = 903, rolledBack = 904;
        insertCampaign(cancelled, "Item09 running", CampaignStatus.RUNNING, null, 0, 0, null);
        insertDeliveryRow(30_901, cancelled, null, "PENDING", "campaign_901_addr_a", null, "item09-a@test.local");
        insertDeliveryRow(30_902, cancelled, null, "QUEUED", "campaign_901_addr_b", Instant.now(), "item09-b@test.local");
        insertCampaign(rolledBack, "Item09 rolled back", CampaignStatus.RUNNING, null, 0, 0, null);
        LocalDateTime now = LocalDateTime.now();
        when(jobExplorer.findRunningJobExecutions(CampaignBatchConfig.JOB_NAME)).thenReturn(Set.of(
                execution(9001, cancelled, now),                       // this campaign's job
                execution(9002, 999, now),                             // another campaign's job
                execution(9003, cancelled, now.minusHours(30)),        // abandoned long ago
                execution(9004, rolledBack, now)));

        // cancel(): CANCELLED, and after the commit Spring Batch is asked to stop the job execution.
        assertThat(service.cancel(cancelled).status()).isEqualTo(CampaignStatus.CANCELLED);
        verify(jobOperator).stop(9001L);
        verify(jobOperator, never()).stop(9002L);
        verify(jobOperator, never()).stop(9003L);
        // A cancel that is rolled back stops nothing.
        tx.executeWithoutResult(s -> {
            service.cancel(rolledBack);
            s.setRollbackOnly();
        });
        verify(jobOperator, never()).stop(9004L);
        assertThat(value("SELECT status FROM campaigns WHERE id = ?", rolledBack)).isEqualTo("RUNNING");

        // The job listener: STOPPED (from JobOperator.stop) or COMPLETED never overwrite CANCELLED.
        CampaignJobListener listener = new CampaignJobListener(campaignRepository);
        tx.executeWithoutResult(s -> listener.afterJob(ended(9001, cancelled, BatchStatus.STOPPED)));
        tx.executeWithoutResult(s -> listener.afterJob(ended(9001, cancelled, BatchStatus.COMPLETED)));
        assertThat(value("SELECT status FROM campaigns WHERE id = ?", cancelled)).isEqualTo("CANCELLED");

        // A campaign deleted with a stale QUEUED row (its message dead-lettered or expired).
        insertCampaign(deleted, "Item09 deleted", CampaignStatus.COMPLETED, null, 0, 0, null);
        insertDeliveryRow(30_903, deleted, null, "PENDING", "campaign_902_addr_a", null, "item09-c@test.local");
        insertDeliveryRow(30_904, deleted, null, "QUEUED", "campaign_902_addr_b",
                Instant.now().minus(26, ChronoUnit.HOURS), "item09-d@test.local");
        service.delete(deleted);
        insertCampaign(paused, "Item09 paused", CampaignStatus.PAUSED, null, 0, 0, null);
        insertDeliveryRow(30_905, paused, null, "PENDING", "campaign_903_addr_a", null, "item09-e@test.local");

        TemplateEngineService templateEngine = mock(TemplateEngineService.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        CampaignItemProcessor processor = new CampaignItemProcessor(
                templateEngine, unsubscribeService, deliveryRows, campaignRepository);
        CampaignItemWriter writer = new CampaignItemWriter(rabbit, deliveryRows, campaignRepository);
        for (long id : List.of(cancelled, deleted, paused)) {
            String key = "campaign_" + id + "_addr_a";
            // The processor doesn't render the items the reader had already read ...
            assertThat(processor.process(rowFor(id, key))).as("processed for campaign %d", id).isNull();
            // ... and the writer publishes nothing for them.
            tx.executeWithoutResult(s -> writer.write(Chunk.of(message(id, key))));
            assertThat(value("SELECT delivery_status FROM campaign_recipients WHERE idempotency_key = ?", key))
                    .as("row of campaign %d", id).isEqualTo("PENDING");
        }
        verifyNoInteractions(templateEngine, rabbit, unsubscribeService);

        // The consumer re-checks the campaign: messages still in RabbitMQ are skipped with a reason.
        EmailProvider smtp = mock(EmailProvider.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(false);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        EmailLogService emailLog = mock(EmailLogService.class);
        EmailConsumer consumer = new EmailConsumer(smtp, deliveryRows, campaignRepository, rateLimiter, emailLog, redis);
        tx.executeWithoutResult(s -> consumer.consume(message(cancelled, "campaign_901_addr_b")));
        tx.executeWithoutResult(s -> consumer.consume(message(deleted, "campaign_902_addr_b")));
        assertThat(row("SELECT delivery_status, last_error FROM campaign_recipients WHERE id = 30902"))
                .containsEntry("DELIVERY_STATUS", "SKIPPED").containsEntry("LAST_ERROR", "Campaign cancelled");
        assertThat(row("SELECT delivery_status, last_error FROM campaign_recipients WHERE id = 30904"))
                .containsEntry("DELIVERY_STATUS", "SKIPPED").containsEntry("LAST_ERROR", "Campaign deleted");
        verifyNoInteractions(smtp, rateLimiter, emailLog);
    }

    // ── 10. Scope: nothing else changes ─────────────────────────────────────────

    @Test
    void item10_only_the_deleted_campaign_changes_and_nothing_else_is_touched() throws Exception {
        long target = 1001, other = 1002, otherDraft = 1003, group = 21_001;
        insertTemplate(21_002);
        insertContact(11_001, "item10-ada@test.local");
        insertContact(11_002, "item10-bob@test.local");
        exec("INSERT INTO recipient_groups (id, name, created_at) VALUES (?, 'Item10 group', CURRENT_TIMESTAMP)", group);
        exec("INSERT INTO recipient_group_members (recipient_id, group_id) VALUES (11001, ?)", group);
        for (long id : List.of(target, other)) {
            insertCampaign(id, "Item10 " + id, CampaignStatus.COMPLETED, 21_002L, 1, 0, null);
            insertTargetRecipient(id, id == target ? 11_001 : 11_002);
            exec("INSERT INTO campaign_target_groups (campaign_id, group_id) VALUES (?, ?)", id, group);
            insertTargetAddress(id, "item10-only-" + id + "@test.local");
            long row = insertDeliveryRow(31_000 + id, id, id == target ? 11_001L : 11_002L, "SENT",
                    "campaign_" + id + "_recipient", null, null);
            insertEmailLog(id, row, id == target ? "item10-ada@test.local" : "item10-bob@test.local", "SENT", Instant.now());
        }
        insertTargetAddress(target, "item10-second@test.local");
        insertCampaign(otherDraft, "Item10 other draft", CampaignStatus.DRAFT, 21_002L, 0, 0, null);
        insertTargetAddress(otherDraft, "item10-draft@test.local");
        insertUnsubscribe(11_003, "item10-gone@test.local", target);
        insertUnsubscribe(11_004, "item10-left@test.local", other);

        Map<String, List<String>> before = snapshotExcept(target);
        Map<String, Object> targetBefore = row("SELECT " + CAMPAIGN_COLUMNS_KEPT_ON_DELETE + " FROM campaigns WHERE id = ?", target);
        assertThat(count("SELECT COUNT(*) FROM campaign_target_addresses WHERE campaign_id = ?", target)).isEqualTo(2);

        service.delete(target);

        // Every other row of every table is exactly as before: other campaigns and their
        // rows, contacts, groups, templates, email logs and unsubscribe entries.
        assertThat(snapshotExcept(target)).isEqualTo(before);
        // The deleted campaign: only deleted_at, deleted_by, version and updated_at change ...
        assertThat(row("SELECT " + CAMPAIGN_COLUMNS_KEPT_ON_DELETE + " FROM campaigns WHERE id = ?", target))
                .isEqualTo(targetBefore);
        assertThat(value("SELECT deleted_at FROM campaigns WHERE id = ?", target)).isNotNull();
        // ... and its campaign-only address list is removed; its delivery rows and logs stay.
        assertThat(count("SELECT COUNT(*) FROM campaign_target_addresses WHERE campaign_id = ?", target)).isZero();
        assertThat(count("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = ?", target)).isOne();
        assertThat(count("SELECT COUNT(*) FROM email_logs WHERE campaign_id = ?", target)).isOne();
    }

    // ── Supporting checks ───────────────────────────────────────────────────────

    @Test
    void v6_adds_exactly_the_soft_delete_columns_that_the_entity_maps() throws Exception {
        Path migrations = Path.of("src", "main", "resources", "db", "migration");
        // The SQL statements of V6, without its comment lines.
        String v6 = String.join("\n", Files.readAllLines(migrations.resolve("V6__campaign_soft_delete.sql")).stream()
                .filter(line -> !line.trim().startsWith("--")).toList());
        assertThat(v6).contains("ALTER TABLE campaigns")
                .contains("ADD COLUMN deleted_at TIMESTAMPTZ")
                .contains("ADD COLUMN deleted_by BIGINT REFERENCES users(id)")
                .contains("CREATE INDEX idx_campaigns_deleted ON campaigns(deleted_at) WHERE deleted_at IS NULL")
                .doesNotContainIgnoringCase("DROP ")
                .doesNotContainIgnoringCase("UPDATE ")
                .doesNotContainIgnoringCase("DELETE ")
                .doesNotContainIgnoringCase("TRUNCATE ")
                .doesNotContainIgnoringCase("NOT NULL");
        assertThat(Campaign.class.getDeclaredField("deletedAt").getAnnotation(Column.class).name()).isEqualTo("deleted_at");
        assertThat(Campaign.class.getDeclaredField("deletedAt").getType()).isEqualTo(Instant.class);
        assertThat(Campaign.class.getDeclaredField("deletedBy").getAnnotation(Column.class).name()).isEqualTo("deleted_by");
        assertThat(Campaign.class.getDeclaredField("deletedBy").getType()).isEqualTo(Long.class);
        // V5 stays as it was: no soft-delete column there.
        assertThat(Files.readString(migrations.resolve("V5__campaign_only_addresses.sql"))).doesNotContain("deleted_at");
    }

    @Test
    void running_job_executions_are_matched_on_the_campaign_id_parameter_and_their_latest_step_update()
            throws Exception {
        LocalDateTime now = LocalDateTime.now();
        JobExecution fresh = execution(1, 5, now.minusMinutes(2));
        JobExecution other = execution(2, 6, now);
        JobExecution abandoned = execution(3, 5, now.minusHours(30));
        // Started long ago, but a step updated it a minute ago: still running.
        JobExecution longRunning = execution(4, 5, now.minusHours(30));
        StepExecution step = longRunning.createStepExecution(CampaignBatchConfig.STEP_NAME);
        step.setLastUpdated(now.minusMinutes(1));
        JobExecution finished = execution(5, 5, now);
        finished.setStatus(BatchStatus.COMPLETED);
        when(jobExplorer.findRunningJobExecutions(CampaignBatchConfig.JOB_NAME))
                .thenReturn(Set.of(fresh, other, abandoned, longRunning, finished));

        assertThat(jobControl.findRunningExecutions(5L)).extracting(JobExecution::getId).containsExactly(1L, 4L);
        assertThat(jobControl.findRunningExecutions(7L)).isEmpty();

        // Stopping an execution that ended in between is harmless; the next one is still stopped.
        doThrow(new JobExecutionNotRunningException("ended")).when(jobOperator).stop(1L);
        jobControl.stopRunningJobs(5L);
        verify(jobOperator).stop(1L);
        verify(jobOperator).stop(4L);
        verify(jobOperator, never()).stop(2L);
        verify(jobOperator, never()).stop(3L);
        verify(jobOperator, never()).stop(5L);
    }

    // ── Helpers: services and transactions ──────────────────────────────────────

    /** Wraps a service like Spring does, so its {@code @Transactional} methods run in real transactions. */
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    /**
     * Runs {@code action} in another thread inside a transaction that loaded the
     * campaign before a delete committed (what happens when two requests overlap), and
     * returns what the action threw (null if it succeeded).
     */
    private Throwable raceAgainstDelete(ExecutorService pool, long id, Runnable action) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        Future<?> racing = pool.submit(() -> tx.executeWithoutResult(s -> {
            campaignRepository.findById(id); // the read the action's own load reuses
            loaded.countDown();
            await(deleted);
            action.run();
        }));
        await(loaded);
        service.delete(id);
        deleted.countDown();
        try {
            racing.get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void signInAs(long userId) {
        User user = User.builder().id(userId).email("manager@test.local").firstName("Mia").lastName("Manager")
                .passwordHash("x").roles(Set.of(new Role("CAMPAIGN_MANAGER"))).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static void assertNotFound(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(ResourceNotFoundException.class);
        assertThat(((MailFlowException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static List<Long> ids(List<?> items) {
        List<Long> out = new ArrayList<>();
        for (Object o : items) {
            out.add(o instanceof Campaign c ? c.getId() : ((com.mailflow.campaign.dto.CampaignResponse) o).id());
        }
        return out;
    }

    private static long totalVolume(List<Map<String, Object>> days) {
        return days.stream().mapToLong(d -> ((Number) d.get("count")).longValue()).sum();
    }

    private static JobExecution execution(long executionId, long campaignId, LocalDateTime lastActivity) {
        JobExecution e = new JobExecution(executionId, new JobParametersBuilder()
                .addLong("campaignId", campaignId).addLong("launchedAt", executionId).toJobParameters());
        e.setStatus(BatchStatus.STARTED);
        e.setCreateTime(lastActivity);
        e.setStartTime(lastActivity);
        e.setLastUpdated(lastActivity);
        return e;
    }

    private static JobExecution ended(long executionId, long campaignId, BatchStatus status) {
        JobExecution e = execution(executionId, campaignId, LocalDateTime.now());
        e.setStatus(status);
        return e;
    }

    private static CampaignRecipient rowFor(long campaignId, String key) {
        return CampaignRecipient.builder()
                .campaign(Campaign.builder().id(campaignId).name("c").subject("s").build())
                .idempotencyKey(key)
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .snapshotEmail(key + "@test.local")
                .build();
    }

    private static EmailMessage message(long campaignId, String key) {
        return EmailMessage.builder()
                .idempotencyKey(key)
                .campaignId(campaignId)
                .toAddress(key + "@test.local")
                .senderEmail("sender@test.local")
                .subject("Hello")
                .htmlBody("<p>Hello</p>")
                .plainBody("Hello")
                .attemptNumber(1)
                .createdAt(Instant.now())
                .build();
    }

    // ── Helpers: JDBC fixtures and snapshots ────────────────────────────────────

    /** Every campaigns column except the four a delete writes (deleted_at, deleted_by, version, updated_at). */
    private static final String CAMPAIGN_COLUMNS_KEPT_ON_DELETE = "id, name, description, subject, template_id, "
            + "sender_name, sender_email, reply_to, status, scheduled_at, created_by, created_at, started_at, "
            + "completed_at, total_recipients, sent_count, failed_count, skipped_count, bounced_count, retry_count, "
            + "batch_job_id, custom_body";

    private static void insertCampaign(long id, String name, CampaignStatus status, Long templateId,
                                       long sent, long failed, Instant scheduledAt) throws SQLException {
        exec("INSERT INTO campaigns (id, name, subject, template_id, sender_email, status, scheduled_at, created_at,"
                        + " updated_at, total_recipients, sent_count, failed_count, skipped_count, bounced_count,"
                        + " retry_count, version) VALUES (?, ?, 'Hello {{FirstName}}', ?, 'sender@test.local', ?, ?,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, 0, 0, 0, 0)",
                id, name, templateId, status.name(), scheduledAt != null ? utc(scheduledAt) : null,
                sent + failed, sent, failed);
    }

    private static void insertTemplate(long id) throws SQLException {
        exec("INSERT INTO email_templates (id, name, subject, html_body, variables, created_at, version)"
                + " VALUES (?, 'Item template', 'Hi', '<p>Hi {{FirstName}}</p>', '[]' FORMAT JSON, CURRENT_TIMESTAMP, 0)", id);
    }

    private static void insertContact(long id, String email) throws SQLException {
        exec("INSERT INTO recipients (id, email, name, custom_fields, status, created_at, version)"
                + " VALUES (?, ?, 'Ada', '{}' FORMAT JSON, 'ACTIVE', CURRENT_TIMESTAMP, 0)", id, email);
    }

    private static void insertTargetRecipient(long campaignId, long recipientId) throws SQLException {
        exec("INSERT INTO campaign_target_recipients (campaign_id, recipient_id) VALUES (?, ?)", campaignId, recipientId);
    }

    private static void insertTargetAddress(long campaignId, String email) throws SQLException {
        exec("INSERT INTO campaign_target_addresses (campaign_id, email, name, custom_fields, created_at)"
                + " VALUES (?, ?, 'Only Once', '{\"Team\":\"Platform\"}' FORMAT JSON, CURRENT_TIMESTAMP)", campaignId, email);
    }

    private static long insertDeliveryRow(long id, long campaignId, Long recipientId, String status, String key,
                                          Instant queuedAt, String snapshotEmail) throws SQLException {
        exec("INSERT INTO campaign_recipients (id, campaign_id, recipient_id, delivery_status, idempotency_key,"
                        + " queued_at, retry_count, email) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                id, campaignId, recipientId, status, key, queuedAt != null ? utc(queuedAt) : null, snapshotEmail);
        return id;
    }

    private static void insertEmailLog(long campaignId, Long deliveryRowId, String to, String status, Instant at)
            throws SQLException {
        exec("INSERT INTO email_logs (campaign_id, campaign_recipient_id, to_address, subject, status,"
                + " attempt_number, processed_at) VALUES (?, ?, ?, 'Hello', ?, 1, ?)", campaignId, deliveryRowId, to,
                status, utc(at));
    }

    private static void insertUnsubscribe(long id, String email, long campaignId) throws SQLException {
        exec("INSERT INTO unsubscribe_list (id, email, token, unsubscribed_at, campaign_id)"
                + " VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)", id, email, "token-" + id, campaignId);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Every row of every table, as text, leaving out the deleted campaign's own row and
     * its campaign-only addresses (the only rows a delete may change).
     */
    private static Map<String, List<String>> snapshotExcept(long deletedCampaign) throws SQLException {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("campaigns", texts("SELECT * FROM campaigns WHERE id <> ? ORDER BY id", deletedCampaign));
        tables.put("campaign_target_addresses",
                texts("SELECT * FROM campaign_target_addresses WHERE campaign_id <> ? ORDER BY id", deletedCampaign));
        for (String[] t : new String[][]{
                {"campaign_recipients", "id"}, {"campaign_target_recipients", "campaign_id, recipient_id"},
                {"campaign_target_groups", "campaign_id, group_id"}, {"recipients", "id"},
                {"recipient_groups", "id"}, {"recipient_group_members", "recipient_id, group_id"},
                {"email_templates", "id"}, {"email_logs", "id"}, {"unsubscribe_list", "id"}}) {
            tables.put(t[0], texts("SELECT * FROM " + t[0] + " ORDER BY " + t[1]));
        }
        return tables;
    }

    private static void exec(String sql, Object... params) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             PreparedStatement ps = prepare(c, sql, params)) {
            ps.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps;
    }

    private static Map<String, Object> row(String sql, Object... params) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             PreparedStatement ps = prepare(c, sql, params);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                Object v = rs.getObject(i);
                out.put(rs.getMetaData().getColumnLabel(i), v instanceof byte[] ? rs.getString(i) : v);
            }
            return out;
        }
    }

    private static Object value(String sql, Object... params) throws SQLException {
        Map<String, Object> r = row(sql, params);
        return r == null ? null : r.values().iterator().next();
    }

    private static long count(String sql, Object... params) throws SQLException {
        return ((Number) value(sql, params)).longValue();
    }

    private static List<String> texts(String sql, Object... params) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             PreparedStatement ps = prepare(c, sql, params);
             ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= columns; i++) sb.append(rs.getString(i)).append('|');
                rows.add(sb.toString());
            }
            return rows;
        }
    }
}
