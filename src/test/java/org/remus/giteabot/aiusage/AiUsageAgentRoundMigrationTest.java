package org.remus.giteabot.aiusage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration gate for the {@code ai_usage_log.agent_round} column: the Usage page
 * shows the agent-loop round of every AI interaction, so a database that never
 * received the column fails the entity mapping ({@code ddl-auto=validate}).
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled) that migrates to the latest version and asserts the column exists.
 * It deliberately pins no version number, so it cannot rot when the next
 * migration is added.</p>
 */
class AiUsageAgentRoundMigrationTest {

    private static final String URL = "jdbc:h2:mem:ai-usage-agent-round;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "classpath:db/migration/h2";

    @Test
    void aiUsageLogCarriesTheAgentRoundColumn() throws Exception {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            assertThat(columnNames(connection)).contains("AGENT_ROUND");
        }
    }

    private static Set<String> columnNames(Connection connection) throws Exception {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'AI_USAGE_LOG'")) {
            while (result.next()) {
                columns.add(result.getString(1));
            }
        }
        return columns;
    }
}
