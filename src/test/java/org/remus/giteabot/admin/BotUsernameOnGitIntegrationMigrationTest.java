package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
 * Migration test for V59 (bot username moves to {@code git_integrations.username}):
 * migrates a scratch H2 database to 57, inserts legacy integrations and bots,
 * applies the dialect's V59 script and asserts the username was copied, the
 * bot column dropped and the integration column made non-nullable.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled).</p>
 */
class BotUsernameOnGitIntegrationMigrationTest {

    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";
    private static final String PLACEHOLDER = "please-fill-git-username";

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    void migrationMovesBotUsernameToGitIntegration(String dialect) throws Exception {
        String url = "jdbc:h2:mem:bot-username-migration-" + dialect + ";DB_CLOSE_DELAY=-1";
        migrateTo(url, "57");

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ai_integrations (name, provider_type, api_url, model,"
                        + " created_at, updated_at) VALUES ('ai','OPENAI','http://x','m',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                insertGitIntegration(s, "with-bot", null);
                insertGitIntegration(s, "with-username", "bot@example.com");
                insertGitIntegration(s, "orphan", null);
                // The former form always submitted the hidden username field, so UI-saved
                // non-Bitbucket integrations hold '' rather than NULL.
                insertGitIntegration(s, "empty-username", "");
                insertGitIntegration(s, "blank-username", "   ");
                insertGitIntegration(s, "empty-orphan", "");
                insertGitIntegration(s, "shared", null);
                insertGitIntegration(s, "shared-blank-first", null);
                insertGitIntegration(s, "bitbucket-null", null, "BITBUCKET");
                insertGitIntegration(s, "bitbucket-empty", "", "BITBUCKET");
                insertGitIntegration(s, "bitbucket-email", "bot@example.com", "BITBUCKET");
            }
            long aiId = q1(c, "SELECT id FROM ai_integrations WHERE name='ai'");
            insertBot(c, "bot", "ai_bot", aiId, gitId(c, "with-bot"));
            insertBot(c, "bitbucket-bot", "unrelated", aiId, gitId(c, "with-username"));
            insertBot(c, "empty-bot", "empty_bot", aiId, gitId(c, "empty-username"));
            insertBot(c, "blank-bot", " blank_bot ", aiId, gitId(c, "blank-username"));
            // Shared integrations: the bot with the lowest id and a non-blank username wins.
            insertBot(c, "shared-first", "first_bot", aiId, gitId(c, "shared"));
            insertBot(c, "shared-second", "second_bot", aiId, gitId(c, "shared"));
            insertBot(c, "shared-blank", "", aiId, gitId(c, "shared-blank-first"));
            insertBot(c, "shared-named", "named_bot", aiId, gitId(c, "shared-blank-first"));
            // Bitbucket identifies the bot by the Atlassian e-mail: a bot username is never copied.
            insertBot(c, "bitbucket-null-bot", "bb_null", aiId, gitId(c, "bitbucket-null"));
            insertBot(c, "bitbucket-empty-bot", "bb_empty", aiId, gitId(c, "bitbucket-empty"));
            insertBot(c, "bitbucket-email-bot", "bb_email", aiId, gitId(c, "bitbucket-email"));

            if ("postgresql".equals(dialect)) {
                // No PostgreSQL container in the test setup: run the PostgreSQL script in H2's
                // PostgreSQL compatibility mode, which is stricter than the default mode.
                try (Statement s = c.createStatement()) {
                    s.execute("SET MODE PostgreSQL");
                }
            }
            ScriptUtils.executeSqlScript(c, new ClassPathResource(
                    "db/migration/" + dialect + "/V59__bot_username_on_git_integration.sql"));

            // Data: the bot username is copied, an existing username is kept,
            // and an integration without bot gets the placeholder.
            assertEquals("ai_bot", username(c, "with-bot"));
            assertEquals("bot@example.com", username(c, "with-username"));
            assertEquals(PLACEHOLDER, username(c, "orphan"));
            // Blank integration usernames count as missing; copied usernames are trimmed.
            assertEquals("empty_bot", username(c, "empty-username"));
            assertEquals("blank_bot", username(c, "blank-username"));
            assertEquals(PLACEHOLDER, username(c, "empty-orphan"));
            assertEquals("first_bot", username(c, "shared"));
            assertEquals("named_bot", username(c, "shared-blank-first"));
            assertEquals(PLACEHOLDER, username(c, "bitbucket-null"));
            assertEquals(PLACEHOLDER, username(c, "bitbucket-empty"));
            assertEquals("bot@example.com", username(c, "bitbucket-email"));

            // Schema: bots.username is gone, git_integrations.username is NOT NULL.
            assertFalse(columnExists(c, "BOTS", "USERNAME"), "bots.username must be dropped");
            assertEquals("NO", isNullable(c, "GIT_INTEGRATIONS", "USERNAME"));
        }
    }

    @Test
    void usernameIsNotNullableAfterTheMigration() throws Exception {
        String url = "jdbc:h2:mem:bot-username-schema;DB_CLOSE_DELAY=-1";
        migrateTo(url, "59");

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(columnExists(c, "BOTS", "USERNAME"), "bots.username must be dropped");
            assertEquals("NO", isNullable(c, "GIT_INTEGRATIONS", "USERNAME"));
        }
    }

    @Test
    void usernameIsDroppedOnceItIsResolvedFromTheProvider() throws Exception {
        String url = "jdbc:h2:mem:git-username-dropped;DB_CLOSE_DELAY=-1";
        migrateTo(url, null);

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(columnExists(c, "GIT_INTEGRATIONS", "USERNAME"), "git_integrations.username must be dropped");
        }
    }

    private static void migrateTo(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(LOCATIONS);
        if (target != null) {
            configuration = configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void insertGitIntegration(Statement s, String name, String username) throws Exception {
        insertGitIntegration(s, name, username, "GITEA");
    }

    private static void insertGitIntegration(Statement s, String name, String username, String providerType)
            throws Exception {
        s.execute("INSERT INTO git_integrations (name, provider_type, url, token, username,"
                + " created_at, updated_at) VALUES ('" + name + "','" + providerType + "','http://x','t', "
                + (username == null ? "NULL" : "'" + username + "'")
                + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static void insertBot(Connection c, String name, String username, long aiId, long gitId)
            throws Exception {
        long promptId = q1(c, "SELECT id FROM system_prompts WHERE default_entry=TRUE");
        long toolCfgId = q1(c, "SELECT id FROM bot_tool_configurations WHERE default_entry=TRUE");
        long prCfgId = q1(c, "SELECT id FROM workflow_configurations WHERE kind='PR' AND default_entry=TRUE");
        long issueCfgId = q1(c, "SELECT id FROM workflow_configurations WHERE kind='ISSUE' AND default_entry=TRUE");
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO bots (name, username, system_prompt_id, bot_tool_configuration_id,"
                    + " workflow_configuration_id, issue_workflow_configuration_id, enabled,"
                    + " ai_integration_id, git_integration_id, run_on_pr_creation, run_on_pr_update,"
                    + " webhook_call_count, ai_tokens_sent, ai_tokens_received, created_at, updated_at)"
                    + " VALUES ('" + name + "', '" + username + "', " + promptId + ", " + toolCfgId + ", "
                    + prCfgId + ", " + issueCfgId + ", TRUE, " + aiId + ", " + gitId
                    + ", FALSE, FALSE, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    private static long gitId(Connection c, String name) throws Exception {
        return q1(c, "SELECT id FROM git_integrations WHERE name='" + name + "'");
    }

    private static String username(Connection c, String integrationName) throws Exception {
        return qs(c, "SELECT username FROM git_integrations WHERE name='" + integrationName + "'");
    }

    private static boolean columnExists(Connection c, String table, String column) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static String isNullable(Connection c, String table, String column) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT is_nullable FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " must exist");
            return rs.getString(1);
        }
    }

    private static long q1(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row: " + sql);
            }
            return rs.getLong(1);
        }
    }

    private static String qs(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row: " + sql);
            }
            return rs.getString(1);
        }
    }
}
