package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitIntegrationSshMigrationTest {

    private static final String URL = "jdbc:h2:mem:git-integration-ssh-migration;DB_CLOSE_DELAY=-1";

    @Test
    void sshTransportMigration_defaultExistingIntegrations() throws Exception {
        migrateTo(URL, "46");
        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO git_integrations (name, provider_type, url, created_at, updated_at)
                    VALUES ('Existing Gitea', 'GITEA', 'https://gitea.example.com', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        migrateTo(URL, "47");

        try (var connection = DriverManager.getConnection(URL, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT transport, ssh_private_key, ssh_known_hosts
                     FROM git_integrations WHERE name = 'Existing Gitea'
                     """)) {
            result.next();
            assertEquals("HTTP", result.getString("transport"));
            assertNull(result.getString("ssh_private_key"));
            assertNull(result.getString("ssh_known_hosts"));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "transport VARCHAR(16) NOT NULL DEFAULT 'HTTP'",
            "ssh_private_key TEXT",
            "ssh_known_hosts TEXT"
    })
    void sshTransportMigration_acceptsExistingColumnsAndRepeatedExecution(String column) throws Exception {
        String url = "jdbc:h2:mem:ssh-existing-" + column.split(" ")[0] + ";DB_CLOSE_DELAY=-1";
        migrateTo(url, "46");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE git_integrations ADD COLUMN " + column);
            migrateTo(url, "47");
            statement.executeUpdate("""
                    INSERT INTO git_integrations
                        (name, provider_type, url, transport, ssh_private_key, ssh_known_hosts, created_at, updated_at)
                    VALUES ('Existing SSH', 'GITEA', 'https://gitea.example.com', 'SSH', 'stored-key', 'stored-hosts',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            // Execute the SQL directly: Flyway would skip an already recorded migration.
            var migration = new ClassPathResource("db/migration/h2/V47__git_integration_ssh_transport.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            try (var result = statement.executeQuery("""
                    SELECT transport, ssh_private_key, ssh_known_hosts
                    FROM git_integrations WHERE name = 'Existing SSH'
                    """)) {
                result.next();
                assertEquals("SSH", result.getString("transport"));
                assertEquals("stored-key", result.getString("ssh_private_key"));
                assertEquals("stored-hosts", result.getString("ssh_known_hosts"));
            }
        }
    }

    private static void migrateTo(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("filesystem:src/main/resources/db/migration/h2")
                .target(target)
                .load()
                .migrate();
    }
}
