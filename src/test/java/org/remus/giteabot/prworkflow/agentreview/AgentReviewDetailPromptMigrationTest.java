package org.remus.giteabot.prworkflow.agentreview;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration test for V2026_11_17_1000: the seeded agentic-review role description stops asking for
 * a short, skimmable review and asks for a detailed assessment and code suggestions; a customised
 * one is left alone.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway disabled).</p>
 */
class AgentReviewDetailPromptMigrationTest {

    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";
    private static final String CUSTOM = "Custom reviewer. Keep it focused and skimmable.";

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void seededPromptAsksForDetailAndCustomPromptIsKept(String dialect) throws Exception {
        String url = "jdbc:h2:mem:agent-review-detail-" + dialect + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations(LOCATIONS)
                .target("2026.11.14.1403").load().migrate();

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            String seededBefore = prompt(c, "default_entry = TRUE");
            assertTrue(seededBefore.contains("Keep it focused and skimmable"),
                    "precondition: the seeded prompt carries the old wording");
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE custom_prompt AS SELECT * FROM system_prompts WHERE default_entry = TRUE");
                s.execute("UPDATE custom_prompt SET id = id + 1000, name = 'custom', default_entry = FALSE,"
                        + " review_agent_system_prompt = '" + CUSTOM + "'");
                String columns = insertableColumns(c);
                s.execute("INSERT INTO system_prompts (" + columns + ") SELECT " + columns + " FROM custom_prompt");
                s.execute("DROP TABLE custom_prompt");
                if ("postgresql".equals(dialect)) {
                    // No PostgreSQL container in the test setup: run the PostgreSQL script in H2's
                    // PostgreSQL compatibility mode.
                    s.execute("SET MODE PostgreSQL");
                }
            }
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/" + dialect + "/V2026_11_17_1000__agentic_review_detail.sql"));

            String seeded = prompt(c, "default_entry = TRUE");
            assertFalse(seeded.contains("Keep it focused and skimmable"));
            assertFalse(seeded.contains("short summary first"));
            assertFalse(seeded.contains("say so briefly"));
            assertTrue(seeded.contains("so do not format the comment yourself."));
            assertTrue(seeded.contains("a detailed overall assessment of the change"));
            assertTrue(seeded.contains("with a code snippet whenever the fix is code."));
            assertTrue(seeded.contains("If the change looks good, say so and explain why."));
            assertTrue(seeded.contains("record the review with the review tools"),
                    "the rest of the role description is kept");

            assertEquals(CUSTOM, prompt(c, "name = 'custom'"));
        }
    }

    /** Every column of system_prompts except the generated ones, which cannot be assigned. */
    private static String insertableColumns(Connection c) throws Exception {
        StringBuilder columns = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT column_name FROM INFORMATION_SCHEMA.COLUMNS WHERE table_name = 'SYSTEM_PROMPTS'"
                        + " AND is_generated = 'NEVER' ORDER BY ordinal_position")) {
            while (rs.next()) {
                columns.append(columns.isEmpty() ? "" : ", ").append(rs.getString(1));
            }
        }
        return columns.toString();
    }

    private static String prompt(Connection c, String where) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT review_agent_system_prompt FROM system_prompts WHERE " + where)) {
            assertTrue(rs.next(), "no system prompt where " + where);
            return rs.getString(1);
        }
    }
}
