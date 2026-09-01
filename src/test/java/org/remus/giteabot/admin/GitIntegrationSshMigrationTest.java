package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitIntegrationSshMigrationTest {

    private static final String URL = "jdbc:h2:mem:git-integration-ssh-migration;DB_CLOSE_DELAY=-1";

    @Test
    void sshMigrations_defaultExistingIntegrationsAndTrackRemoteKeys() throws Exception {
        migrateTo("46");
        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO git_integrations (name, provider_type, url, created_at, updated_at)
                    VALUES ('Existing Gitea', 'GITEA', 'https://gitea.example.com', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        migrateTo("50");

        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT transport, ssh_private_key, ssh_known_hosts, ssh_remote_key_id,
                            ssh_remote_key_owner_id, ssh_remote_key_title
                     FROM git_integrations WHERE name = 'Existing Gitea'
                     """)) {
            result.next();
            assertEquals("HTTP", result.getString("transport"));
            assertNull(result.getString("ssh_private_key"));
            assertNull(result.getString("ssh_known_hosts"));
            assertNull(result.getObject("ssh_remote_key_id"));
            assertNull(result.getObject("ssh_remote_key_owner_id"));
            assertNull(result.getString("ssh_remote_key_title"));
        }

        String largeKey = "k".repeat(5_000);
        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.prepareStatement(
                     "UPDATE git_integrations SET ssh_private_key = ? WHERE name = 'Existing Gitea'")) {
            statement.setString(1, largeKey);
            statement.executeUpdate();
        }
        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT ssh_private_key FROM git_integrations WHERE name = 'Existing Gitea'")) {
            result.next();
            assertEquals(largeKey, result.getString(1));
        }
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("filesystem:src/main/resources/db/migration/h2")
                .target(target)
                .load()
                .migrate();
    }
}
