package org.remus.giteabot.database;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A fresh H2 database or an owned PostgreSQL schema, without starting the application. */
public final class FlywayDatabase implements AutoCloseable {
    private final DriverManagerDataSource dataSource;
    private final Connection owner;
    private final String vendor;
    private final String schema;

    private FlywayDatabase(DriverManagerDataSource dataSource, Connection owner, String vendor, String schema) {
        this.dataSource = dataSource;
        this.owner = owner;
        this.vendor = vendor;
        this.schema = schema;
    }

    /** Opens an empty database; PostgreSQL is optional locally unless TEST_POSTGRES_REQUIRED=true. */
    public static FlywayDatabase open(String vendor) throws SQLException {
        return switch (vendor) {
            case "h2" -> {
                var source = new DriverManagerDataSource("jdbc:h2:mem:rr_contract_" + UUID.randomUUID(), "sa", "");
                yield new FlywayDatabase(source, source.getConnection(), vendor, "PUBLIC");
            }
            case "postgresql" -> postgres(System.getenv());
            default -> throw new IllegalArgumentException("Unsupported contract database vendor");
        };
    }

    static FlywayDatabase postgres(Map<String, String> environment) throws SQLException {
        String required = environment.getOrDefault("TEST_POSTGRES_REQUIRED", "false");
        if (!required.equals("true") && !required.equals("false")) {
            throw new IllegalArgumentException("TEST_POSTGRES_REQUIRED must be true or false");
        }
        String url = environment.get("TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            if (required.equals("true")) {
                throw new IllegalArgumentException("TEST_POSTGRES_URL is required for PostgreSQL contracts");
            }
            assumeTrue(false, "PostgreSQL contract skipped: set TEST_POSTGRES_URL and TEST_POSTGRES_USER; "
                    + "CI must set TEST_POSTGRES_REQUIRED=true");
        }
        // Keep credentials out of Flyway's URL logs and refuse non-test databases.
        if (!url.matches("jdbc:postgresql://(?:[a-zA-Z0-9._-]+|\\[[0-9a-fA-F:]+])(?::[0-9]{1,5})?/ai_git_bot_contracts")) {
            throw new IllegalArgumentException("TEST_POSTGRES_URL must use jdbc:postgresql://host:port/"
                    + "ai_git_bot_contracts without credentials or URL parameters");
        }
        String user = environment.get("TEST_POSTGRES_USER");
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("TEST_POSTGRES_USER is required when TEST_POSTGRES_URL is set");
        }
        var source = new DriverManagerDataSource(url, user, environment.getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        source.setConnectionProperties(properties);
        Connection owner = source.getConnection();
        String schema = "rr_contract_" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (!owner.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                throw new SQLException("PostgreSQL contracts require a real PostgreSQL server");
            }
            try (var statement = owner.createStatement()) {
                statement.executeUpdate("CREATE SCHEMA " + schema);
            }
        } catch (SQLException | RuntimeException failure) {
            try {
                owner.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        return new FlywayDatabase(source, owner, "postgresql", schema);
    }

    /** Uses the entire shipped vendor-specific chain, with no baseline, target override or clean. */
    public Flyway flyway() {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/" + vendor)
                .defaultSchema(schema).schemas(schema).createSchemas(false).cleanDisabled(true)
                .load();
    }

    /** Each connection uses this fixture's schema; callers own their connections and transactions. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The schema owned by this fixture, never supplied by the caller. */
    public String schema() {
        return schema;
    }

    /** Removes only the generated PostgreSQL schema, or releases the last H2 in-memory connection. */
    @Override
    public void close() throws SQLException {
        try (owner) {
            if (vendor.equals("postgresql")) {
                try (var statement = owner.createStatement()) {
                    statement.executeUpdate("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }
}
