package com.mailflow.email;

import com.mailflow.email.entity.UnsubscribeEntry;
import com.mailflow.email.repository.UnsubscribeRepository;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the unsubscribe-list lookup used by the upload preview and /lookup through
 * Hibernate against the real UnsubscribeEntry mapping, on an in-memory H2 database
 * with a hand-written table, and evaluates UnsubscribeService's cache-key expressions.
 * PostgreSQL and the Spring cache itself are not used.
 */
class UnsubscribeListQueryTest {

    private static final String URL = "jdbc:h2:mem:unsubscribe_list_query;DB_CLOSE_DELAY=-1";

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE unsubscribe_list (
                        id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL UNIQUE,
                        token VARCHAR(64) NOT NULL UNIQUE, reason VARCHAR(1000),
                        unsubscribed_at TIMESTAMP WITH TIME ZONE NOT NULL, campaign_id BIGINT)""");
            s.execute("""
                    INSERT INTO unsubscribe_list (id, email, token, unsubscribed_at, campaign_id) VALUES
                        (1, 'ada@test.local', 't1', CURRENT_TIMESTAMP, NULL),
                        (2, 'bob@test.local', 't2', CURRENT_TIMESTAMP, 7)""");
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", URL)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(UnsubscribeEntry.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) sessionFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void find_emails_in_returns_only_the_listed_addresses() throws Exception {
        String jpql = UnsubscribeRepository.class.getMethod("findEmailsIn", Collection.class)
                .getAnnotation(Query.class).value();

        try (Session session = sessionFactory.openSession()) {
            List<String> found = session.createQuery(jpql, String.class)
                    .setParameter("emails", List.of("ada@test.local", "bob@test.local", "nobody@test.local"))
                    .getResultList();
            assertThat(found).containsExactlyInAnyOrder("ada@test.local", "bob@test.local");

            assertThat(session.createQuery(jpql, String.class)
                    .setParameter("emails", List.of("nobody@test.local"))
                    .getResultList()).isEmpty();
        }
    }

    @Test
    void the_cache_keys_are_the_normalised_address() throws Exception {
        // The "unsubscribed" cache is read when sending and evicted on unsubscribe; both
        // keys must be the same normalised address, whatever spelling each caller uses.
        String readKey = UnsubscribeService.class.getMethod("isUnsubscribed", String.class)
                .getAnnotation(Cacheable.class).key();
        String evictKey = UnsubscribeService.class.getMethod("unsubscribe", String.class, String.class, Long.class)
                .getAnnotation(CacheEvict.class).key();
        assertThat(evictKey).isEqualTo(readKey);

        SpelExpressionParser parser = new SpelExpressionParser();
        for (String spelling : List.of("ada@test.local", " Ada@Test.Local\u00A0", "\u200BADA@TEST.LOCAL\uFEFF")) {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("email", spelling);
            assertThat(parser.parseExpression(readKey).getValue(context)).isEqualTo("ada@test.local");
        }
    }
}
