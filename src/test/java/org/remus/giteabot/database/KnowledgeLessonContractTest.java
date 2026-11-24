package org.remus.giteabot.database;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.learning.knowledge.KnowledgeLesson;
import org.remus.giteabot.learning.knowledge.LessonState;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateSettings;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lesson tables on both shipped engines, migrated by the real Flyway chain: revision numbers
 * are unique per lesson, a Git comment can cause at most one lesson event while system events need
 * no comment, and two concurrent transitions of one lesson cannot both commit — the optimistic lock
 * the service relies on holds on H2 and PostgreSQL alike.
 */
@Isolated("migrates a full fixture; see FlywayDatabaseContractTest")
class KnowledgeLessonContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void revisionNumbersAreUniquePerLesson(String vendor) throws Exception {
        try (var database = FlywayDatabase.open(vendor)) {
            database.flyway().migrate();
            var sql = new JdbcTemplate(database.dataSource());
            long lesson = insertLesson(sql);
            insertRevision(sql, lesson, 1);
            insertRevision(sql, lesson, 2);

            assertThrows(DataIntegrityViolationException.class, () -> insertRevision(sql, lesson, 2));
            insertRevision(sql, insertLesson(sql), 2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void aCommentCausesAtMostOneEventWhileSystemEventsNeedNoComment(String vendor) throws Exception {
        try (var database = FlywayDatabase.open(vendor)) {
            database.flyway().migrate();
            var sql = new JdbcTemplate(database.dataSource());
            long lesson = insertLesson(sql);
            insertEvent(sql, lesson, null);
            insertEvent(sql, lesson, null);
            insertEvent(sql, lesson, "a".repeat(64));

            assertThrows(DataIntegrityViolationException.class,
                    () -> insertEvent(sql, lesson, "a".repeat(64)));
            assertEquals(3, sql.queryForObject(
                    "SELECT COUNT(*) FROM learning_events WHERE lesson_id = ?", Integer.class, lesson));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void theReferenceCounterForLessonsIsSeeded(String vendor) throws Exception {
        try (var database = FlywayDatabase.open(vendor)) {
            database.flyway().migrate();
            var sql = new JdbcTemplate(database.dataSource());

            assertEquals(1L, sql.queryForObject(
                    "SELECT next_value FROM reference_sequences WHERE name = 'knowledge_lesson'",
                    Long.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void twoConcurrentTransitionsOfOneLessonCannotBothCommit(String vendor) throws Exception {
        try (var database = FlywayDatabase.open(vendor)) {
            database.flyway().migrate();
            var factory = entityManagerFactory(database);
            try {
                EntityManagerFactory emf = factory.getObject();
                Long id = persistLesson(emf);

                try (EntityManager first = emf.createEntityManager(); EntityManager second = emf.createEntityManager()) {
                    first.getTransaction().begin();
                    second.getTransaction().begin();
                    KnowledgeLesson mine = first.find(KnowledgeLesson.class, id);
                    KnowledgeLesson theirs = second.find(KnowledgeLesson.class, id);

                    mine.setActiveRevision(1);
                    mine.setState(LessonState.ACTIVE);
                    first.getTransaction().commit();

                    theirs.setActiveRevision(1);
                    theirs.setState(LessonState.ACTIVE);
                    RollbackException lost = assertThrows(RollbackException.class,
                            () -> second.getTransaction().commit());
                    assertInstanceOf(OptimisticLockException.class, lost.getCause());
                }
                assertEquals(1L, new JdbcTemplate(database.dataSource()).queryForObject(
                        "SELECT version FROM knowledge_lessons WHERE id = ?", Long.class, id));
            } finally {
                factory.destroy();
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static LocalContainerEntityManagerFactoryBean entityManagerFactory(FlywayDatabase database) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(database.dataSource());
        factory.setPackagesToScan("org.remus.giteabot");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(new HibernateProperties().determineHibernateProperties(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", database.schema()), new HibernateSettings()));
        factory.afterPropertiesSet();
        return factory;
    }

    private static Long persistLesson(EntityManagerFactory emf) {
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            KnowledgeLesson lesson = new KnowledgeLesson();
            lesson.setReference("L" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000));
            lesson.setGitIntegrationId(1L);
            lesson.setBotId(2L);
            lesson.setRepoOwner("acme");
            lesson.setRepoName("web");
            lesson.setWorkflowKey("agentic-review");
            lesson.setLatestRevision(1);
            lesson.setCreatedBy("alex");
            em.persist(lesson);
            em.getTransaction().commit();
            return lesson.getId();
        }
    }

    private static long insertLesson(JdbcTemplate sql) {
        String reference = "L" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        sql.update("""
                INSERT INTO knowledge_lessons (reference, git_integration_id, bot_id, repo_owner,
                    repo_name, workflow_key, state, latest_revision, review_required, created_by,
                    created_at, updated_at, version)
                VALUES (?, 1, 2, 'acme', 'web', 'agentic-review', 'CANDIDATE', 1, FALSE, 'alex', ?, ?, 0)
                """, reference, now, now);
        return sql.queryForObject("SELECT id FROM knowledge_lessons WHERE reference = ?", Long.class,
                reference);
    }

    private static void insertRevision(JdbcTemplate sql, long lesson, int number) {
        sql.update("""
                INSERT INTO knowledge_lesson_revisions (lesson_id, revision_number, state, lesson_text,
                    content_hash, created_by, created_at)
                VALUES (?, ?, 'CANDIDATE', 'Inspect the filter.', ?, 'alex', ?)
                """, lesson, number, "0".repeat(64), Timestamp.from(Instant.now()));
    }

    private static void insertEvent(JdbcTemplate sql, long lesson, String sourceKey) {
        sql.update("""
                INSERT INTO learning_events (lesson_id, revision_number, event_type, actor, source_key,
                    created_at)
                VALUES (?, 1, 'PROPOSED', 'alex', ?, ?)
                """, lesson, sourceKey, Timestamp.from(Instant.now()));
    }
}
