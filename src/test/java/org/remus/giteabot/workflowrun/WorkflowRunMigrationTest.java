package org.remus.giteabot.workflowrun;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow-run migration moves the pre-run usage and error logs into one legacy run per
 * audit session id: owner, repository and number are parsed from {@code owner/repo#n}, rows
 * without a session share one run, interactions are numbered by time, and the old tables are
 * dropped.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway disabled): migrates to
 * the version before the workflow-run migration, seeds the old tables, then migrates to the
 * latest version.</p>
 */
class WorkflowRunMigrationTest {

    private static final String URL = "jdbc:h2:mem:workflow-run-migration;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "classpath:db/migration/h2";

    @Test
    void legacyUsageAndErrorsBecomeOneRunPerSession() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "").locations(LOCATIONS)
                .target("2026.11.17.1400").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_usage_log (recorded_at, ai_integration_name, session_id, "
                    + "input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens, "
                    + "agent_round, raw_request) VALUES "
                    + "(TIMESTAMP '2026-01-01 10:00:00', 'claude', 'owner/repo#42', 100, 10, 5, 50, 1, '{\"r\":1}'), "
                    + "(TIMESTAMP '2026-01-01 10:02:00', 'claude', 'owner/repo#42', 200, 20, 0, 0, 2, NULL), "
                    + "(TIMESTAMP '2026-01-02 09:00:00', 'claude', 'group/sub/project#7', 1, 1, 0, 0, NULL, NULL), "
                    + "(TIMESTAMP '2026-01-03 09:00:00', 'openai', NULL, 3, 3, 0, 0, NULL, NULL)");
            statement.executeUpdate("INSERT INTO ai_error_log (recorded_at, ai_integration_name, session_id, "
                    + "error_message, stack_trace) VALUES "
                    + "(TIMESTAMP '2026-01-01 10:01:00', 'claude', 'owner/repo#42', '529 overloaded', 'trace')");
        }

        Flyway.configure().dataSource(URL, "sa", "").locations(LOCATIONS).load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            List<String> runs = rows(statement, "SELECT r.workflow_type, r.repo_owner, r.repo_name, "
                    + "r.trigger_number, r.state, COUNT(i.id), SUM(i.input_tokens), SUM(i.output_tokens), "
                    + "SUM(i.cache_read_input_tokens) FROM workflow_run r JOIN ai_interaction i "
                    + "ON i.workflow_run_id = r.id GROUP BY r.id, r.workflow_type, r.repo_owner, r.repo_name, "
                    + "r.trigger_number, r.state, r.started_at ORDER BY r.started_at");
            assertThat(runs).containsExactly(
                    "legacy|owner|repo|42|ERROR|3|300|30|50",
                    "legacy|group/sub|project|7|SUCCEEDED|1|1|1|0",
                    "legacy|null|null|null|SUCCEEDED|1|3|3|0");

            List<String> interactions = rows(statement, "SELECT i.round_number, i.attempt, i.status, "
                    + "i.error_message, i.raw_request FROM ai_interaction i JOIN workflow_run r "
                    + "ON r.id = i.workflow_run_id WHERE r.trigger_number = 42 ORDER BY i.round_number");
            assertThat(interactions).containsExactly(
                    "1|1|SUCCEEDED|null|{\"r\":1}",
                    "2|1|ERROR|529 overloaded|null",
                    "3|1|SUCCEEDED|null|null");

            List<String> oldTables = rows(statement, "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME IN ('AI_USAGE_LOG', 'AI_ERROR_LOG')");
            assertThat(oldTables).isEmpty();

            // The audit session id is dropped again once runs carry the repository and number.
            List<String> sessionColumns = rows(statement, "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE COLUMN_NAME = 'SESSION_ID' AND TABLE_NAME IN ('WORKFLOW_RUN', 'AI_INTERACTION')");
            assertThat(sessionColumns).isEmpty();
        }
    }

    private static List<String> rows(Statement statement, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(result.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }
}
