package org.remus.giteabot.database;

import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;
import org.remus.giteabot.admin.AdminUser;
import org.springframework.boot.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("V12/V14 constraint guards inspect all PostgreSQL schemas")
class FlywayDatabaseContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void freshDatabaseMigratesTheFullChainAndValidatesJpa(String vendor) throws Exception {
        try (var database = FlywayDatabase.open(vendor)) {
            try (var connection = database.dataSource().getConnection()) {
                assertEquals(vendor.equals("h2") ? "H2" : "PostgreSQL",
                        connection.getMetaData().getDatabaseProductName());
                assertEquals(database.schema(), connection.getSchema());
            }
            var flyway = database.flyway();
            int migrations = flyway.info().pending().length;
            assertTrue(migrations > 0, "The shipped migration chain must be discovered");
            assertEquals(migrations, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(migrations, flyway.info().applied().length);
            assertEquals(0, flyway.info().pending().length);
            assertEquals(0, flyway.migrate().migrationsExecuted, "A second startup must not rerun migrations");
            validateJpaAndTransactions(database, vendor);
        }
    }

    private static void validateJpaAndTransactions(FlywayDatabase database, String vendor) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(database.dataSource());
        factory.setPackagesToScan("org.remus.giteabot");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        // Use Boot's physical and implicit naming defaults, but detect the actual fixture's dialect.
        factory.setJpaPropertyMap(new HibernateProperties().determineHibernateProperties(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", database.schema()), new HibernateSettings()));
        try {
            factory.afterPropertiesSet();
            var entityManagerFactory = factory.getObject();
            assertNotNull(entityManagerFactory);
            assertEquals(SpringImplicitNamingStrategy.class.getName(),
                    entityManagerFactory.getProperties().get("hibernate.implicit_naming_strategy"));
            assertEquals(vendor.equals("h2") ? H2Dialect.class : PostgreSQLDialect.class,
                    entityManagerFactory.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect().getClass());
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            var transactions = new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));
            var user = new AdminUser();
            user.setUsername("committed-contract-user");
            user.setPasswordHash("not-a-real-password-hash");
            transactions.executeWithoutResult(status -> {
                entityManager.persist(user);
                entityManager.flush();
            });
            transactions.executeWithoutResult(status -> {
                var reloaded = entityManager.find(AdminUser.class, user.getId());
                assertNotNull(reloaded);
                assertEquals("committed-contract-user", reloaded.getUsername());
                reloaded.setUsername("rolled-back-contract-user");
                entityManager.flush();
                status.setRollbackOnly();
            });
            var sql = new JdbcTemplate(database.dataSource());
            assertEquals("committed-contract-user", sql.queryForObject(
                    "SELECT username FROM admin_users WHERE id = ?", String.class, user.getId()));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void postgresCleanupRemovesOnlyItsOwnedSchema() throws Exception {
        try (var sibling = FlywayDatabase.open("postgresql")) {
            var sql = new JdbcTemplate(sibling.dataSource());
            sql.execute("CREATE TABLE contract_sentinel (id INTEGER PRIMARY KEY)");
            sql.update("INSERT INTO contract_sentinel (id) VALUES (1)");
            String disposableSchema;
            try (var disposable = FlywayDatabase.open("postgresql")) {
                disposableSchema = disposable.schema();
                assertNotEquals(sibling.schema(), disposableSchema);
                var disposableSql = new JdbcTemplate(disposable.dataSource());
                disposableSql.execute("CREATE TABLE contract_sentinel (id INTEGER PRIMARY KEY)");
                assertEquals(0, disposableSql.queryForObject("SELECT COUNT(*) FROM contract_sentinel", Integer.class));
            }
            assertEquals(0, sql.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                    Integer.class, disposableSchema));
            assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM contract_sentinel", Integer.class));
        }
    }

    @Test
    void missingRequiredPostgresConfigurationFailsInsteadOfSkipping() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FlywayDatabase.postgres(Map.of("TEST_POSTGRES_REQUIRED", "true")));
        assertTrue(failure.getMessage().contains("TEST_POSTGRES_URL"));
    }

    @Test
    void missingOptionalPostgresConfigurationExplicitlySkips() {
        var skipped = assertThrows(TestAbortedException.class, () -> FlywayDatabase.postgres(Map.of()));
        assertTrue(skipped.getMessage().contains("PostgreSQL contract skipped"));
    }

    @Test
    void configuredPostgresRequiresAnExplicitUser() {
        var failure = assertThrows(IllegalArgumentException.class, () -> FlywayDatabase.postgres(Map.of(
                "TEST_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/ai_git_bot_contracts")));
        assertTrue(failure.getMessage().contains("TEST_POSTGRES_USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:not-postgres", "jdbc:postgresql://127.0.0.1:5432/production",
            "jdbc:postgresql://contract_test:secret-sentinel@127.0.0.1:5432/ai_git_bot_contracts",
            "jdbc:postgresql://127.0.0.1:5432/ai_git_bot_contracts?password=secret-sentinel"})
    void refusesOtherDatabasesAndCredentialsInUrlsWithoutLoggingTheirValues(String url) {
        var failure = assertThrows(IllegalArgumentException.class, () -> FlywayDatabase.postgres(Map.of(
                "TEST_POSTGRES_URL", url, "TEST_POSTGRES_USER", "contract_test")));
        assertFalse(failure.getMessage().contains(url));
        assertFalse(failure.getMessage().contains("secret-sentinel"));
    }

    @Test
    void misspelledRequiredFlagCannotSilentlyDisablePostgres() {
        assertThrows(IllegalArgumentException.class,
                () -> FlywayDatabase.postgres(Map.of("TEST_POSTGRES_REQUIRED", "tru")));
    }

    @Test
    void unsupportedVendorCannotFallBackToAnotherEngine() {
        assertThrows(IllegalArgumentException.class, () -> FlywayDatabase.open("unknown"));
    }
}
