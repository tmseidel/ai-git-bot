package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.GitAuthor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration gate for the {@code git_integrations.git_author_name} /
 * {@code git_integrations.git_author_email} columns: the entity maps them as
 * non-null, so an integration created before the setting existed must come out
 * of the migration with the identity its bots' commits used before
 * ({@link GitAuthor#DEFAULT}), not with a blank or missing author.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled). It migrates to the previous version, persists a legacy
 * integration, then migrates to V60 and asserts the integration received the
 * default identity.</p>
 */
class GitIntegrationGitAuthorMigrationTest {

    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    private static Flyway flyway(String url, Integer target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(LOCATIONS);
        if (target != null) {
            configuration = configuration.target(target.toString());
        }
        return configuration.load();
    }

    @Test
    void migrationAssignsTheDefaultIdentityToExistingIntegrations() throws Exception {
        String url = "jdbc:h2:mem:git-integration-git-author-upgrade;DB_CLOSE_DELAY=-1";
        flyway(url, 59).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO git_integrations (name, provider_type, url, username, created_at, updated_at) "
                    + "VALUES ('legacy-git', 'GITEA', 'https://gitea.example.com', 'legacy-user', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        flyway(url, 60).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT git_author_name, git_author_email FROM git_integrations WHERE name = 'legacy-git'")) {
            assertTrue(result.next(), "the legacy integration must still exist");
            assertEquals(GitAuthor.DEFAULT_NAME, result.getString(1));
            assertEquals(GitAuthor.DEFAULT_EMAIL, result.getString(2));
        }
    }

    @Test
    void columnsAreNotNullableAfterTheMigration() throws Exception {
        String url = "jdbc:h2:mem:git-integration-git-author-schema;DB_CLOSE_DELAY=-1";
        flyway(url, null).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT column_name, is_nullable FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'GIT_INTEGRATIONS' "
                             + "AND COLUMN_NAME IN ('GIT_AUTHOR_NAME', 'GIT_AUTHOR_EMAIL') "
                             + "ORDER BY column_name")) {
            assertTrue(result.next(), "the git_author_email column must exist");
            assertEquals("GIT_AUTHOR_EMAIL", result.getString(1));
            assertEquals("NO", result.getString(2));
            assertTrue(result.next(), "the git_author_name column must exist");
            assertEquals("GIT_AUTHOR_NAME", result.getString(1));
            assertEquals("NO", result.getString(2));
        }
    }
}
