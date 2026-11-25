package com.mailflow.batch;

import com.mailflow.auth.entity.Role;
import com.mailflow.auth.entity.User;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.entity.EmailTemplate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Runs the batch reader query and the new repository queries through Hibernate
 * against the real entity mappings, on an in-memory H2 database with hand-written
 * tables. PostgreSQL itself is not used here; the live schema is checked by
 * Hibernate's {@code ddl-auto: validate} at application start.
 */
class CampaignOnlyReaderQueryTest {

    private static final String URL = "jdbc:h2:mem:campaign_only_reader;DB_CLOSE_DELAY=-1";

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE recipients (organization_id BIGINT, 
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
            s.execute("""
                    CREATE TABLE campaign_target_addresses (
                        id BIGINT PRIMARY KEY, campaign_id BIGINT NOT NULL, email VARCHAR(255) NOT NULL,
                        name VARCHAR(255), first_name VARCHAR(100), last_name VARCHAR(100),
                        company VARCHAR(255), custom_fields JSON, created_at TIMESTAMP WITH TIME ZONE)""");

            s.execute("""
                    INSERT INTO recipients (id, email, name, custom_fields, status, deleted_at, version) VALUES
                        (1, 'active@test.local', 'Active Contact', JSON '{"code":"A1"}', 'ACTIVE', NULL, 0),
                        (2, 'optout@test.local', NULL, JSON '{}', 'UNSUBSCRIBED', NULL, 0),
                        (3, 'Gone@Test.Local', NULL, JSON '{}', 'ACTIVE', CURRENT_TIMESTAMP, 0)""");
            s.execute("""
                    INSERT INTO campaign_recipients
                        (id, campaign_id, recipient_id, delivery_status, idempotency_key, retry_count,
                         email, name, first_name, custom_fields) VALUES
                        (10, 7, 1,    'PENDING', 'campaign_7_recipient_1', 0, NULL, NULL, NULL, NULL),
                        (11, 7, NULL, 'PENDING', 'campaign_7_addr_only', 0,
                             'only@test.local', 'Only Address', 'Only', JSON '{"Team":"Platform"}'),
                        (12, 7, 2,    'PENDING', 'campaign_7_recipient_2', 0, NULL, NULL, NULL, NULL),
                        (13, 7, 3,    'PENDING', 'campaign_7_recipient_3', 0, NULL, NULL, NULL, NULL),
                        (14, 7, NULL, 'QUEUED',  'campaign_7_addr_queued', 0, 'queued@test.local', NULL, NULL, JSON '{}'),
                        (15, 7, NULL, 'FAILED',  'campaign_7_addr_retry', 1, 'retry@test.local', NULL, NULL, JSON '{}'),
                        (16, 7, NULL, 'SENT',    'campaign_7_addr_sent', 0, 'sent@test.local', NULL, NULL, JSON '{}'),
                        (17, 8, NULL, 'PENDING', 'campaign_8_addr_other', 0, 'other@test.local', NULL, NULL, JSON '{}')""");
            s.execute("""
                    INSERT INTO campaign_target_addresses (id, campaign_id, email, custom_fields) VALUES
                        (1, 7, 'only@test.local', JSON '{}'),
                        (2, 7, 'retry@test.local', JSON '{}'),
                        (3, 8, 'other@test.local', JSON '{}')""");
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
                        Recipient.class, EmailTemplate.class, User.class, Role.class, com.mailflow.organization.entity.Organization.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    private static String queryOf(Class<?> repository, String method, Class<?>... params) throws Exception {
        return repository.getMethod(method, params).getAnnotation(Query.class).value();
    }

    @Test
    void reader_reads_campaign_only_rows_and_active_contacts_only() {
        try (Session session = sessionFactory.openSession()) {
            List<CampaignRecipient> rows = session
                    .createQuery(CampaignBatchConfig.READER_QUERY, CampaignRecipient.class)
                    .setParameter("campaignId", 7L)
                    .getResultList();

            // PENDING/FAILED rows of campaign 7 whose contact is ACTIVE and not deleted,
            // plus rows without a contact. Not: UNSUBSCRIBED (12) or deleted (13)
            // contacts, QUEUED (14) / SENT (16) rows, or another campaign's row (17).
            assertThat(rows).extracting(CampaignRecipient::getId).containsExactly(10L, 11L, 15L);
            assertThat(rows.get(0).getRecipient().getEmail()).isEqualTo("active@test.local");

            CampaignRecipient only = rows.get(1);
            assertThat(only.getRecipient()).isNull();
            assertThat(only.targetEmail()).isEqualTo("only@test.local");
            assertThat(only.getSnapshotName()).isEqualTo("Only Address");
            assertThat(only.getSnapshotFirstName()).isEqualTo("Only");
            assertThat(only.getSnapshotCustomFields()).containsExactly(entry("Team", "Platform"));
        }
    }

    @Test
    void reader_pages_with_the_left_join() {
        try (Session session = sessionFactory.openSession()) {
            List<CampaignRecipient> firstPage = session
                    .createQuery(CampaignBatchConfig.READER_QUERY, CampaignRecipient.class)
                    .setParameter("campaignId", 7L)
                    .setFirstResult(0)
                    .setMaxResults(2)
                    .getResultList();

            assertThat(firstPage).extracting(CampaignRecipient::getId).containsExactly(10L, 11L);
        }
    }

    @Test
    void targeted_emails_cover_contact_and_campaign_only_rows_in_lower_case() throws Exception {
        String hql = queryOf(CampaignRecipientRepository.class, "findTargetedEmails", Long.class);
        try (Session session = sessionFactory.openSession()) {
            List<String> emails = session.createQuery(hql, String.class)
                    .setParameter("campaignId", 7L)
                    .getResultList();

            assertThat(emails).containsExactlyInAnyOrder(
                    "active@test.local", "only@test.local", "optout@test.local", "gone@test.local",
                    "queued@test.local", "retry@test.local", "sent@test.local");
        }
    }

    @Test
    void target_address_counts_are_grouped_per_campaign() throws Exception {
        String hql = queryOf(CampaignTargetAddressRepository.class, "countByCampaignIds", Collection.class);
        try (Session session = sessionFactory.openSession()) {
            Map<Long, Long> counts = session.createQuery(hql, Object[].class)
                    .setParameter("campaignIds", List.of(7L, 8L, 9L))
                    .getResultList().stream()
                    .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()));

            assertThat(counts).containsOnly(entry(7L, 2L), entry(8L, 1L));
        }
    }
}
