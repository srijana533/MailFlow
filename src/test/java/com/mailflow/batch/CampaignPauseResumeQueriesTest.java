package com.mailflow.batch;

import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.config.MailFlowProperties;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.entity.EmailTemplate;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the pause/resume pieces against the real entity mappings on an in-memory H2
 * database with hand-written tables: the batch reader (stops after a pause, a resume
 * reads only unsent rows), the conditional status update used by the job listener,
 * the consumer's held/skipped row updates and row lock, and {@code @DynamicUpdate} on
 * Campaign. PostgreSQL itself is not used.
 */
class CampaignPauseResumeQueriesTest {

    private static final String URL = "jdbc:h2:mem:campaign_pause_resume;DB_CLOSE_DELAY=-1";

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE campaigns (
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
                    CREATE TABLE recipients (
                        id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL, name VARCHAR(255),
                        first_name VARCHAR(100), last_name VARCHAR(100), company VARCHAR(255),
                        course VARCHAR(255), custom_fields JSON, status VARCHAR(50) NOT NULL,
                        import_source VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE,
                        updated_at TIMESTAMP WITH TIME ZONE, deleted_at TIMESTAMP WITH TIME ZONE,
                        version BIGINT NOT NULL)""");
            s.execute("""
                    CREATE TABLE campaign_recipients (
                        id BIGINT PRIMARY KEY, campaign_id BIGINT NOT NULL, recipient_id BIGINT,
                        delivery_status VARCHAR(50) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
                        queued_at TIMESTAMP WITH TIME ZONE, sent_at TIMESTAMP WITH TIME ZONE,
                        failed_at TIMESTAMP WITH TIME ZONE, retry_count INT NOT NULL,
                        last_error VARCHAR(1000), personalized_subject VARCHAR(1000),
                        email VARCHAR(255), name VARCHAR(255), first_name VARCHAR(100),
                        last_name VARCHAR(100), company VARCHAR(255), custom_fields JSON)""");
        }

        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClasses(Campaign.class, CampaignRecipient.class, CampaignTargetAddress.class,
                        Recipient.class, EmailTemplate.class, User.class, Role.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int jdbc(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    private static String jdbcString(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long jdbcLong(String sql) throws SQLException {
        return Long.parseLong(jdbcString(sql));
    }

    private static void insertCampaign(long id, String status, long sentCount) throws SQLException {
        jdbc("INSERT INTO campaigns (id, name, subject, status, total_recipients, sent_count, failed_count,"
                + " skipped_count, bounced_count, retry_count, version) VALUES ("
                + id + ", 'Campaign " + id + "', 'Hello', '" + status + "', 0, " + sentCount + ", 0, 0, 0, 0, 0)");
    }

    private static void insertRow(long id, long campaignId, String status, String key) throws SQLException {
        jdbc("INSERT INTO campaign_recipients (id, campaign_id, recipient_id, delivery_status, idempotency_key,"
                + " queued_at, retry_count, email) VALUES (" + id + ", " + campaignId + ", NULL, '" + status + "', '"
                + key + "', " + ("QUEUED".equals(status) ? "CURRENT_TIMESTAMP" : "NULL") + ", 0, '" + key + "@test.local')");
    }

    private static String rowStatus(String key) throws SQLException {
        return jdbcString("SELECT delivery_status FROM campaign_recipients WHERE idempotency_key = '" + key + "'");
    }

    private static void setRows(List<String> keys, String status) throws SQLException {
        jdbc("UPDATE campaign_recipients SET delivery_status = '" + status + "' WHERE idempotency_key IN ('"
                + String.join("', '", keys) + "')");
    }

    private static String queryOf(Class<?> repository, String method, Class<?>... params) throws Exception {
        return repository.getMethod(method, params).getAnnotation(Query.class).value();
    }

    private static int execute(String hql, Object... nameValuePairs) {
        try (Session session = sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            var query = session.createMutationQuery(hql);
            for (int i = 0; i < nameValuePairs.length; i += 2) {
                query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
            }
            int changed = query.executeUpdate();
            tx.commit();
            return changed;
        }
    }

    /** The real step-scoped reader, with its status check reading the campaigns table. */
    private static JpaPagingItemReader<CampaignRecipient> openReader(long campaignId) throws Exception {
        CampaignRepository statusSource = mock(CampaignRepository.class);
        when(statusSource.findStatusById(campaignId)).thenAnswer(inv -> Optional.ofNullable(
                jdbcString("SELECT status FROM campaigns WHERE id = " + campaignId)).map(CampaignStatus::valueOf));
        MailFlowProperties properties = new MailFlowProperties(); // chunk (page) size 50
        CampaignBatchConfig config = new CampaignBatchConfig(null, null, properties, sessionFactory,
                null, null, null, null, null, null, statusSource);

        JpaPagingItemReader<CampaignRecipient> reader = config.campaignItemReader(campaignId);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());
        return reader;
    }

    /** Reads at most {@code max} rows (one chunk) and returns their keys. */
    private static List<String> readChunk(JpaPagingItemReader<CampaignRecipient> reader, int max) throws Exception {
        List<String> keys = new ArrayList<>();
        CampaignRecipient row;
        while (keys.size() < max && (row = reader.read()) != null) {
            keys.add(row.getIdempotencyKey());
        }
        return keys;
    }

    // ── Reader ──────────────────────────────────────────────────────────────────

    @Test
    void reader_stops_at_the_next_page_after_a_pause_and_a_resume_reads_only_unsent_rows() throws Exception {
        insertCampaign(70, "RUNNING", 0);
        for (int i = 1; i <= 120; i++) insertRow(7000 + i, 70, "PENDING", "campaign_70_addr_" + i);

        JpaPagingItemReader<CampaignRecipient> firstRun = openReader(70);
        List<String> firstChunk = readChunk(firstRun, 50);
        assertThat(firstChunk).hasSize(50);
        setRows(firstChunk, "QUEUED"); // what the writer does for the chunk

        jdbc("UPDATE campaigns SET status = 'PAUSED' WHERE id = 70");
        // The next page is not read: end of input, so the step (and the job) ends.
        assertThat(firstRun.read()).isNull();
        firstRun.close();
        assertThat(jdbcLong("SELECT COUNT(*) FROM campaign_recipients WHERE campaign_id = 70"
                + " AND delivery_status = 'PENDING'")).isEqualTo(70);

        // While paused, the consumer held 10 queued messages (back to PENDING through the
        // real query) and had already sent the other 40.
        String toPending = queryOf(CampaignRecipientRepository.class, "returnQueuedToPending", String.class);
        for (String key : firstChunk.subList(0, 10)) {
            assertThat(execute(toPending, "key", key)).isOne();
        }
        setRows(firstChunk.subList(10, 50), "SENT");

        // Resume: a new run reads the 80 unsent rows, never the 40 sent ones.
        jdbc("UPDATE campaigns SET status = 'RUNNING' WHERE id = 70");
        JpaPagingItemReader<CampaignRecipient> resumed = openReader(70);
        List<String> readOnResume = new ArrayList<>();
        List<String> chunk;
        while (!(chunk = readChunk(resumed, 50)).isEmpty()) {
            readOnResume.addAll(chunk);
            setRows(chunk, "QUEUED");
        }
        resumed.close();

        assertThat(readOnResume).hasSize(80).doesNotHaveDuplicates()
                .containsAll(firstChunk.subList(0, 10))
                .doesNotContainAnyElementsOf(firstChunk.subList(10, 50));
    }

    @Test
    void reader_reads_nothing_for_a_campaign_cancelled_before_its_job_started() throws Exception {
        insertCampaign(77, "CANCELLED", 0);
        insertRow(7701, 77, "PENDING", "campaign_77_addr_1");

        JpaPagingItemReader<CampaignRecipient> reader = openReader(77);
        assertThat(reader.read()).isNull();
        reader.close();
        assertThat(rowStatus("campaign_77_addr_1")).isEqualTo("PENDING");
    }

    // ── Job listener's conditional status update ────────────────────────────────

    @Test
    void conditional_status_update_changes_only_a_campaign_in_the_expected_status() throws Exception {
        insertCampaign(71, "PAUSED", 3);
        insertCampaign(72, "RUNNING", 3);
        insertCampaign(73, "CANCELLED", 3);
        String hql = queryOf(CampaignRepository.class, "updateStatusIfIn",
                Long.class, CampaignStatus.class, Collection.class, Instant.class);

        // A job finishing after a pause, a normal finish, and a job starting after a cancel.
        assertThat(execute(hql, "id", 71L, "status", CampaignStatus.COMPLETED,
                "expected", List.of(CampaignStatus.RUNNING), "now", Instant.now())).isZero();
        assertThat(execute(hql, "id", 72L, "status", CampaignStatus.COMPLETED,
                "expected", List.of(CampaignStatus.RUNNING), "now", Instant.now())).isOne();
        assertThat(execute(hql, "id", 73L, "status", CampaignStatus.RUNNING,
                "expected", List.of(CampaignStatus.QUEUED), "now", Instant.now())).isZero();

        assertThat(jdbcString("SELECT status FROM campaigns WHERE id = 71")).isEqualTo("PAUSED");
        assertThat(jdbcString("SELECT status FROM campaigns WHERE id = 72")).isEqualTo("COMPLETED");
        assertThat(jdbcString("SELECT status FROM campaigns WHERE id = 73")).isEqualTo("CANCELLED");
        assertThat(jdbcLong("SELECT sent_count FROM campaigns WHERE id = 72")).isEqualTo(3);
    }

    // ── Consumer: held and skipped rows, row lock ───────────────────────────────

    @Test
    void held_and_cancelled_messages_change_only_queued_rows() throws Exception {
        insertCampaign(76, "PAUSED", 0);
        insertRow(7601, 76, "QUEUED", "k76_held");
        insertRow(7602, 76, "SENT", "k76_sent_a");
        insertRow(7603, 76, "QUEUED", "k76_cancelled");
        insertRow(7604, 76, "SENT", "k76_sent_b");
        String toPending = queryOf(CampaignRecipientRepository.class, "returnQueuedToPending", String.class);
        String skip = queryOf(CampaignRecipientRepository.class, "skipQueued", String.class, Instant.class, String.class);

        assertThat(execute(toPending, "key", "k76_held")).isOne();
        assertThat(execute(toPending, "key", "k76_sent_a")).isZero();
        assertThat(execute(skip, "key", "k76_cancelled", "now", Instant.now(), "reason", "Campaign cancelled")).isOne();
        assertThat(execute(skip, "key", "k76_sent_b", "now", Instant.now(), "reason", "Campaign cancelled")).isZero();

        assertThat(rowStatus("k76_held")).isEqualTo("PENDING");
        assertThat(jdbcString("SELECT queued_at FROM campaign_recipients WHERE idempotency_key = 'k76_held'")).isNull();
        assertThat(rowStatus("k76_sent_a")).isEqualTo("SENT");
        assertThat(rowStatus("k76_cancelled")).isEqualTo("SKIPPED");
        assertThat(jdbcString("SELECT last_error FROM campaign_recipients WHERE idempotency_key = 'k76_cancelled'"))
                .isEqualTo("Campaign cancelled");
        assertThat(rowStatus("k76_sent_b")).isEqualTo("SENT");
    }

    @Test
    void the_consumers_campaign_lock_makes_a_concurrent_status_change_wait() throws Exception {
        insertCampaign(75, "PAUSED", 0);
        Method method = CampaignRepository.class.getMethod("findByIdForUpdate", Long.class);
        String hql = method.getAnnotation(Query.class).value();
        LockModeType mode = method.getAnnotation(Lock.class).value();

        try (Session consumer = sessionFactory.openSession()) {
            Transaction tx = consumer.beginTransaction();
            Campaign locked = consumer.createQuery(hql, Campaign.class)
                    .setParameter("id", 75L).setLockMode(mode).getSingleResult();
            assertThat(locked.getStatus()).isEqualTo(CampaignStatus.PAUSED);

            // A resume updating the same row must wait; H2 gives up after the lock timeout.
            try (Connection resume = DriverManager.getConnection(URL, "sa", "");
                 Statement st = resume.createStatement()) {
                st.execute("SET LOCK_TIMEOUT 300");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE campaigns SET status = 'QUEUED' WHERE id = 75"))
                        .isInstanceOf(SQLException.class);
            }
            tx.commit();
        }
        // Once the consumer's transaction has ended, the resume goes through.
        assertThat(jdbc("UPDATE campaigns SET status = 'QUEUED' WHERE id = 75")).isOne();
    }

    // ── @DynamicUpdate ──────────────────────────────────────────────────────────

    @Test
    void saving_a_status_change_keeps_counter_increments_made_after_the_campaign_was_loaded() throws Exception {
        insertCampaign(74, "RUNNING", 5);
        String increment = queryOf(CampaignRepository.class, "incrementCounters",
                Long.class, long.class, long.class, long.class, long.class, Instant.class);

        try (Session pause = sessionFactory.openSession()) {
            Transaction tx = pause.beginTransaction();
            Campaign campaign = pause.get(Campaign.class, 74L); // what pause() loads
            assertThat(campaign.getSentCount()).isEqualTo(5);

            // Meanwhile the email consumer records a sent email (a bulk update that
            // doesn't change the version, so the optimistic lock can't catch it).
            assertThat(execute(increment, "id", 74L, "sent", 1L, "failed", 0L, "skipped", 0L,
                    "retries", 0L, "now", Instant.now())).isOne();

            campaign.setStatus(CampaignStatus.PAUSED);
            tx.commit();
        }

        assertThat(jdbcString("SELECT status FROM campaigns WHERE id = 74")).isEqualTo("PAUSED");
        // A full-row update would have written back the sent count loaded above (5).
        assertThat(jdbcLong("SELECT sent_count FROM campaigns WHERE id = 74")).isEqualTo(6);
        assertThat(jdbcLong("SELECT version FROM campaigns WHERE id = 74")).isEqualTo(1);
    }
}
