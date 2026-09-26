package org.remus.giteabot.agent.session;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration gate for V53 (answer-only completion): the {@code agent_sessions.status} CHECK
 * constraint must gain the new {@code ANSWERED} value without losing the existing ones.
 *
 * <p>Standalone Flyway/H2 probe, following the recipe of
 * {@link org.remus.giteabot.issueworkflow.IssueWorkflowMigrationTest}. It asserts on
 * {@code INFORMATION_SCHEMA.CHECK_CONSTRAINTS} rather than inserting rows on purpose: H2 2.4
 * cannot evaluate this table's CHECK constraint from a raw JDBC connection — every INSERT fails
 * with {@code 23514 Check constraint invalid} ("the database has been closed" from
 * {@code ConditionInConstantSet}), for pre-existing values and before V53 just the same — while
 * Hibernate-driven inserts are unaffected. {@link AgentSessionAnsweredStatusTest} proves the
 * persistence path end to end.</p>
 */
class AgentSessionAnsweredMigrationTest {

    private static final String URL = "jdbc:h2:mem:agent-session-answered-migration;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";
    private static final String CONSTRAINT = "CHK_AGENT_SESSIONS_STATUS";

    /** Values the constraint allowed before V53 — extending must not drop any of them. */
    private static final List<String> PRE_EXISTING = List.of(
            "IN_PROGRESS", "PR_CREATED", "UPDATING", "COMPLETED", "FAILED", "ISSUE_CREATED");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .target(target)
                .load();
    }

    private static String statusConstraint(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS"
                     + " WHERE CONSTRAINT_NAME = '" + CONSTRAINT + "'")) {
            assertThat(rs.next()).as("%s exists", CONSTRAINT).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v53_extendsTheStatusConstraintWithAnswered() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            flyway("51").migrate();

            // Pre-condition: V5 enumerates the allowed values, so ANSWERED is not accepted yet.
            String at51 = statusConstraint(c);
            assertThat(at51).doesNotContain("ANSWERED");
            assertThat(PRE_EXISTING).allSatisfy(value -> assertThat(at51).contains("'" + value + "'"));

            flyway("53").migrate();

            // Extended, not dropped: ANSWERED is allowed and every previous value still is.
            String at53 = statusConstraint(c);
            assertThat(at53).contains("'ANSWERED'");
            assertThat(PRE_EXISTING).allSatisfy(value -> assertThat(at53).contains("'" + value + "'"));
        }
    }
}
