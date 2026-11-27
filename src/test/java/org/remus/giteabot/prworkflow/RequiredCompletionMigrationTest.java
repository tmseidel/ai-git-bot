package org.remus.giteabot.prworkflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** H2 upgrade smoke test; PostgreSQL SQL here does not qualify a PostgreSQL upgrade. */
class RequiredCompletionMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void nullableLegacyKeysAndPerEndpointRequiredKeys(String scriptDialect) throws Exception {
        String url = "jdbc:h2:mem:required-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:src/main/resources/db/migration/h2").target("59").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO event_hook_endpoint (id, name, url, event_types, created_at, updated_at)
                    VALUES (1, 'first', 'http://localhost', 'PR_WORKFLOW_COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                           (2, 'second', 'http://localhost', 'PR_WORKFLOW_COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_hook_delivery (delivery_uuid, endpoint_id, event_type, payload_json, status, created_at)
                    VALUES ('legacy-1', 1, 'prworkflow.completed', '{}', 'PENDING', CURRENT_TIMESTAMP),
                           ('legacy-2', 1, 'prworkflow.completed', '{}', 'PENDING', CURRENT_TIMESTAMP)
                    """);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + scriptDialect
                    + "/V2026_10_18_1359__required_workflow_completion_event_keys.sql"));
            try (var column = connection.getMetaData().getColumns(null, null,
                    "PR_WORKFLOW_RUNS", "REQUIRED_COMPLETION_HASH")) {
                assertThat(column.next()).isTrue();
                assertThat(column.getInt("DATA_TYPE")).isEqualTo(Types.VARCHAR);
                assertThat(column.getInt("COLUMN_SIZE")).isEqualTo(64);
                assertThat(column.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
                assertThat(column.getString("COLUMN_DEF")).isNull();
            }
            try (var legacy = statement.executeQuery("SELECT COUNT(*) FROM event_hook_delivery WHERE event_key IS NULL")) {
                legacy.next();
                assertThat(legacy.getInt(1)).isEqualTo(2);
            }
            statement.executeUpdate("""
                    INSERT INTO event_hook_delivery (delivery_uuid, endpoint_id, event_type, event_key, payload_json, status, created_at)
                    VALUES ('required-1', 1, 'prworkflow.completed', 'PR_WORKFLOW_RUN_COMPLETED:42:', '{}', 'PENDING', CURRENT_TIMESTAMP),
                           ('required-2', 2, 'prworkflow.completed', 'PR_WORKFLOW_RUN_COMPLETED:42:', '{}', 'PENDING', CURRENT_TIMESTAMP)
                    """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO event_hook_delivery (delivery_uuid, endpoint_id, event_type, event_key, payload_json, status, created_at)
                    VALUES ('duplicate', 1, 'prworkflow.completed', 'PR_WORKFLOW_RUN_COMPLETED:42:', '{}', 'PENDING', CURRENT_TIMESTAMP)
                    """));
            statement.executeUpdate("""
                    INSERT INTO pr_audit_events (event_type, event_timestamp, hash, bot_id, repo_owner, repo_name,
                                                pr_number, actor_type, created_at)
                    VALUES ('REVIEW_COMPLETED', CURRENT_TIMESTAMP, 'hash', 1, 'owner', 'repo', 42, 'SYSTEM', CURRENT_TIMESTAMP),
                           ('REVIEW_COMPLETED', CURRENT_TIMESTAMP, 'hash', 1, 'owner', 'repo', 42, 'SYSTEM', CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO pr_audit_events (event_type, event_key, event_timestamp, hash, bot_id, repo_owner, repo_name,
                                                pr_number, actor_type, created_at)
                    VALUES ('REVIEW_COMPLETED', 'REVIEW_COMPLETED:42:', CURRENT_TIMESTAMP, 'hash', 1, 'owner', 'repo', 42,
                            'SYSTEM', CURRENT_TIMESTAMP)
                    """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO pr_audit_events (event_type, event_key, event_timestamp, hash, bot_id, repo_owner, repo_name,
                                                pr_number, actor_type, created_at)
                    VALUES ('REVIEW_COMPLETED', 'REVIEW_COMPLETED:42:', CURRENT_TIMESTAMP, 'hash', 1, 'owner', 'repo', 42,
                            'SYSTEM', CURRENT_TIMESTAMP)
                    """));
        }
    }
}
