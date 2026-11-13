package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated provisional V63 checks, not qualification of the missing V59–V62 release prefix. */
class McpReadOnlyApprovalMigrationTest {

    @Test
    void h2ExistingRowsAndNewSelectionsStartUnapproved() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:mcp-approval-v63", "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE mcp_configurations (id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO mcp_configurations VALUES (1)");
                statement.execute("INSERT INTO mcp_configurations VALUES (2)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V8__mcp_selected_tools.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO mcp_selected_tools (mcp_configuration_id, qualified_name, server_name, tool_name) "
                        + "VALUES (1, 'mcp:docs:old', 'docs', 'old')");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V63__review_mcp_read_only_approval.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO mcp_configurations (id) VALUES (3)");
                try (var revisions = statement.executeQuery("SELECT review_approval_revision FROM mcp_configurations")) {
                    var unique = new java.util.HashSet<String>();
                    while (revisions.next()) {
                        assertNotNull(revisions.getString(1));
                        java.util.UUID.fromString(revisions.getString(1));
                        assertTrue(unique.add(revisions.getString(1)), "each configuration needs an independent opaque revision");
                    }
                    assertEquals(3, unique.size());
                }
                statement.execute("INSERT INTO mcp_selected_tools (mcp_configuration_id, qualified_name, server_name, tool_name) "
                        + "VALUES (1, 'mcp:docs:new', 'docs', 'new')");
                try (var rows = statement.executeQuery("SELECT review_read_only_approved, review_approval_schema_hash, "
                        + "review_approval_configuration_hash FROM mcp_selected_tools")) {
                    int count = 0;
                    while (rows.next()) {
                        assertFalse(rows.getBoolean(1));
                        assertFalse(rows.wasNull());
                        assertNull(rows.getString(2));
                        assertNull(rows.getString(3));
                        count++;
                    }
                    assertEquals(2, count);
                }
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                        "UPDATE mcp_selected_tools SET review_read_only_approved = NULL"));
            }
        }
    }
}
