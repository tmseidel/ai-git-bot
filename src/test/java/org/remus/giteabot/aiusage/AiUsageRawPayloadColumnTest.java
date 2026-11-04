package org.remus.giteabot.aiusage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AiUsageProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AI_USAGE_MAX_RAW_PAYLOAD_LENGTH=0} stores raw payloads in full, which relies on
 * {@code raw_request}/{@code raw_response} being unbounded in the migrated schema. Standalone
 * Flyway/H2 probe, as the Spring test profile runs with Flyway disabled.
 */
class AiUsageRawPayloadColumnTest {

    private static final String URL = "jdbc:h2:mem:ai-usage-raw-payload;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "classpath:db/migration/h2";

    @Test
    void migratedColumnsHoldPayloadsFarBeyond65535Characters() throws Exception {
        Flyway.configure().dataSource(URL, "sa", "").locations(LOCATIONS).load().migrate();
        String payload = "x".repeat(200_000);

        try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_usage_log (recorded_at, ai_integration_name, input_tokens, output_tokens, "
                            + "raw_request, raw_response) VALUES (CURRENT_TIMESTAMP, 'probe', 0, 0, ?, ?)")) {
                insert.setString(1, payload);
                insert.setString(2, payload);
                insert.executeUpdate();
            }
            try (Statement select = connection.createStatement();
                 ResultSet result = select.executeQuery("SELECT raw_request, raw_response FROM ai_usage_log "
                         + "WHERE ai_integration_name = 'probe'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).hasSize(200_000);
                assertThat(result.getString(2)).hasSize(200_000);
            }
        }
    }

    @Test
    void zeroOrNegativeMaxLengthMeansNoTruncation() {
        AiUsageProperties properties = new AiUsageProperties();
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(65535);

        properties.setMaxRawPayloadLength(0);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(Integer.MAX_VALUE);
        properties.setMaxRawPayloadLength(-1);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(Integer.MAX_VALUE);
        // the old 65535 cap is gone: the columns are TEXT
        properties.setMaxRawPayloadLength(500_000);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(500_000);
    }
}
