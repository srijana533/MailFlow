package com.mailflow.tenant;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Company isolation through Hibernate's tenant filter, with the real entity mappings
 * and {@link TenantIdentifierResolver} on an in-memory H2 database. Two companies each
 * own a contact and a campaign.
 */
class TenantIsolationTest {

    private static final String URL = "jdbc:h2:mem:tenant_isolation;DB_CLOSE_DELAY=-1";
    private static final long ORG_A = 1, ORG_B = 2;

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE recipients (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY, organization_id BIGINT NOT NULL,
                        email VARCHAR(255) NOT NULL, name VARCHAR(255), first_name VARCHAR(100),
                        last_name VARCHAR(100), company VARCHAR(255), course VARCHAR(255),
                        custom_fields JSON, status VARCHAR(50) NOT NULL, import_source VARCHAR(100),
                        created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE,
                        deleted_at TIMESTAMP WITH TIME ZONE, version BIGINT NOT NULL,
                        UNIQUE (organization_id, email))""");
            s.execute("""
                    CREATE TABLE campaigns (
                        id BIGINT PRIMARY KEY, organization_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL,
                        description VARCHAR(1000), subject VARCHAR(1000) NOT NULL, custom_body VARCHAR(1000),
                        template_id BIGINT, sender_name VARCHAR(255), sender_email VARCHAR(255),
                        reply_to VARCHAR(255), status VARCHAR(50) NOT NULL, scheduled_at TIMESTAMP WITH TIME ZONE,
                        created_by BIGINT, created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE,
                        started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE,
                        total_recipients BIGINT DEFAULT 0, sent_count BIGINT DEFAULT 0, failed_count BIGINT DEFAULT 0,
                        skipped_count BIGINT DEFAULT 0, bounced_count BIGINT DEFAULT 0, retry_count BIGINT DEFAULT 0, batch_job_id VARCHAR(255),
                        deleted_at TIMESTAMP WITH TIME ZONE, deleted_by BIGINT, version BIGINT NOT NULL)""");
            s.execute("CREATE TABLE campaign_target_groups (campaign_id BIGINT, group_id BIGINT)");
            s.execute("CREATE TABLE campaign_target_recipients (campaign_id BIGINT, recipient_id BIGINT)");
            // The same address is a contact of both companies.
            s.execute("""
                    INSERT INTO recipients (id, organization_id, email, status, version) VALUES
                        (10, 1, 'shared@test.local', 'ACTIVE', 0),
                        (20, 2, 'shared@test.local', 'ACTIVE', 0)""");
            s.execute("ALTER TABLE recipients ALTER COLUMN id RESTART WITH 100");
            s.execute("""
                    INSERT INTO campaigns (id, organization_id, name, subject, status, version) VALUES
                        (11, 1, 'A launch', 'Hi', 'DRAFT', 0),
                        (21, 2, 'B launch', 'Hi', 'DRAFT', 0)""");
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.tenant_identifier_resolver", new TenantIdentifierResolver())
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClasses(Recipient.class, Campaign.class,
                        com.mailflow.template.entity.EmailTemplate.class,
                        com.mailflow.auth.entity.User.class, com.mailflow.auth.entity.Role.class,
                        com.mailflow.organization.entity.Organization.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @AfterEach
    void signOut() {
        TestTenant.signOut();
    }

    @Test
    void a_company_lists_only_its_own_contacts_and_campaigns() {
        TestTenant.signIn(ORG_A);
        try (Session s = sessionFactory.openSession()) {
            assertThat(s.createQuery("SELECT r FROM Recipient r", Recipient.class).getResultList())
                    .extracting(Recipient::getId).containsExactly(10L);
            assertThat(s.createQuery("SELECT c FROM Campaign c", Campaign.class).getResultList())
                    .extracting(Campaign::getId).containsExactly(11L);
        }
        TestTenant.signIn(ORG_B);
        try (Session s = sessionFactory.openSession()) {
            assertThat(s.createQuery("SELECT r FROM Recipient r", Recipient.class).getResultList())
                    .extracting(Recipient::getId).containsExactly(20L);
        }
    }

    @Test
    void the_campaign_lookup_by_id_does_not_return_another_companys_campaign() throws Exception {
        String jpql = CampaignRepository.class.getMethod("findOneById", Long.class)
                .getAnnotation(Query.class).value();
        TestTenant.signIn(ORG_A);
        try (Session s = sessionFactory.openSession()) {
            assertThat(s.createQuery(jpql, Campaign.class).setParameter("id", 11L).getResultList()).hasSize(1);
            assertThat(s.createQuery(jpql, Campaign.class).setParameter("id", 21L).getResultList()).isEmpty();
        }
    }

    @Test
    void a_new_row_belongs_to_the_signed_in_company() throws Exception {
        TestTenant.signIn(ORG_B);
        try (Session s = sessionFactory.openSession()) {
            s.beginTransaction();
            Recipient r = Recipient.builder().email("new@test.local").status(RecipientStatus.ACTIVE).build();
            s.persist(r);
            s.getTransaction().commit();
            assertThat(r.getOrganizationId()).isEqualTo(ORG_B);
        }
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT organization_id FROM recipients WHERE email = 'new@test.local'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(ORG_B);
        }
    }

    @Test
    void code_without_a_signed_in_user_sees_every_company_and_must_scope_itself() {
        // The batch job, consumer and scheduler run like this; their queries pass the
        // campaign's organization explicitly (see RecipientRepository.findByIdsIn).
        try (Session s = sessionFactory.openSession()) {
            List<Long> ids = s.createQuery("SELECT r.id FROM Recipient r WHERE r.email = 'shared@test.local' ORDER BY r.id",
                    Long.class).getResultList();
            assertThat(ids).containsExactly(10L, 20L);
            assertThat(s.createQuery("SELECT r.id FROM Recipient r WHERE r.organizationId = :org AND r.email = 'shared@test.local'",
                    Long.class).setParameter("org", ORG_B).getResultList()).containsExactly(20L);
        }
    }
}
