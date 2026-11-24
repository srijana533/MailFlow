package com.mailflow.recipient;

import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the saved-contact search query (used by the campaign form's contact picker and
 * the Recipients page) through Hibernate against the real Recipient mapping, on an
 * in-memory H2 database with a hand-written table. PostgreSQL itself is not used.
 */
class RecipientSearchQueryTest {

    private static final String URL = "jdbc:h2:mem:recipient_search;DB_CLOSE_DELAY=-1";

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;
    private static String searchQuery;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE recipients (
                        id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL, name VARCHAR(255),
                        first_name VARCHAR(100), last_name VARCHAR(100), company VARCHAR(255),
                        course VARCHAR(255), custom_fields JSON, status VARCHAR(50) NOT NULL,
                        import_source VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE,
                        updated_at TIMESTAMP WITH TIME ZONE, deleted_at TIMESTAMP WITH TIME ZONE,
                        version BIGINT NOT NULL)""");
            s.execute("""
                    INSERT INTO recipients (id, email, name, first_name, last_name, company, status, deleted_at, version) VALUES
                        (1, 'ada@test.local',     'Ada Lovelace', 'Ada',       'Lovelace', 'Analytical Engines', 'ACTIVE', NULL, 0),
                        (2, 'grace@test.local',   'Grace Hopper', 'Alan',      'Turing',   'Navy',               'ACTIVE', NULL, 0),
                        (3, 'kj@test.local',      NULL,           'Katherine', 'Johnson',  NULL,                 'ACTIVE', NULL, 0),
                        (4, 'gone@test.local',    'Katherine Gone', 'Katherine', 'Gone',   NULL,                 'ACTIVE', CURRENT_TIMESTAMP, 0),
                        (5, 'optout@test.local',  'Opt Out',      'Katherine', 'Out',      NULL,          'UNSUBSCRIBED', NULL, 0)""");
        }

        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(Recipient.class)
                .buildMetadata()
                .buildSessionFactory();
        searchQuery = RecipientRepository.class
                .getMethod("findAllActive", String.class, RecipientStatus.class, Pageable.class)
                .getAnnotation(Query.class).value();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    private static List<Long> search(String text, RecipientStatus status) {
        try (Session session = sessionFactory.openSession()) {
            return session.createQuery(searchQuery, Recipient.class)
                    .setParameter("search", text)
                    .setParameter("status", status)
                    .getResultList().stream()
                    .map(Recipient::getId)
                    .sorted()
                    .toList();
        }
    }

    @Test
    void search_matches_name_email_and_company() {
        assertThat(search("hopper", null)).containsExactly(2L);
        assertThat(search("ADA@TEST", null)).containsExactly(1L);
        assertThat(search("navy", null)).containsExactly(2L);
        assertThat(search("engines", null)).containsExactly(1L);
    }

    @Test
    void search_matches_first_and_last_name_even_when_the_name_differs_or_is_empty() {
        // The picker shows "first last" when there is no name, and templates personalise
        // with the first name, so both must be searchable.
        assertThat(search("alan", null)).containsExactly(2L);
        assertThat(search("turing", null)).containsExactly(2L);
        assertThat(search("johnson", null)).containsExactly(3L);
        assertThat(search("katherine johnson", null)).containsExactly(3L);
        assertThat(search("alan turing", null)).containsExactly(2L);
    }

    @Test
    void search_skips_deleted_contacts_and_applies_the_status_filter() {
        assertThat(search("katherine", null)).containsExactly(3L, 5L);
        assertThat(search("katherine", RecipientStatus.ACTIVE)).containsExactly(3L);
        assertThat(search(null, null)).containsExactly(1L, 2L, 3L, 5L);
        assertThat(search(null, RecipientStatus.ACTIVE)).containsExactly(1L, 2L, 3L);
        assertThat(search("nobody", null)).isEmpty();
    }
}
