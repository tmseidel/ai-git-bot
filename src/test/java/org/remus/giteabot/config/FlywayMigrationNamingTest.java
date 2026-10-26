package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Flyway migration naming scheme.
 *
 * <p>Migrations up to {@code V58} use a plain sequence number. Those files are
 * already recorded in {@code flyway_schema_history} of existing installations
 * and must never be renamed. Every new migration uses a timestamp version
 * ({@code V<yyyy>_<MM>_<dd>_<HHmm>__<description>.sql}) so parallel pull
 * requests do not compete for the next number; Flyway runs with
 * {@code out-of-order=true} so a migration merged after a newer one still
 * gets applied.</p>
 *
 * <p>Flyway silently skips files it cannot parse, so this test also rejects
 * anything that matches neither scheme, and it keeps the H2 and PostgreSQL
 * directories on the same set of versions.</p>
 */
class FlywayMigrationNamingTest {

    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");
    private static final List<String> VENDORS = List.of("h2", "postgresql");

    /** Highest sequential version; anything above must use a timestamp. */
    private static final int LAST_SEQUENTIAL_VERSION = 59;

    /** Historic versions that exist for only one vendor. */
    private static final Set<String> VENDOR_SPECIFIC_VERSIONS = Set.of("4");

    private static final Pattern SEQUENTIAL = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");
    private static final Pattern TIMESTAMP =
            Pattern.compile("^V(\\d{4}_\\d{2}_\\d{2}_\\d{4})__[a-z0-9_]+\\.sql$");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu_MM_dd_HHmm").withResolverStyle(ResolverStyle.STRICT);

    @Test
    void migrationsUseSequentialOrTimestampVersions() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String vendor : VENDORS) {
            for (String file : migrationFiles(vendor)) {
                String problem = validate(file);
                if (problem != null) {
                    violations.add(vendor + "/" + file + ": " + problem);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Invalid migration file names:\n" + String.join("\n", violations));
    }

    @Test
    void versionsAreUniquePerVendor() throws IOException {
        for (String vendor : VENDORS) {
            List<String> versions = migrationFiles(vendor).stream().map(FlywayMigrationNamingTest::version).toList();
            Set<String> duplicates = new TreeSet<>();
            Set<String> seen = new TreeSet<>();
            for (String v : versions) {
                if (!seen.add(v)) {
                    duplicates.add(v);
                }
            }
            assertTrue(duplicates.isEmpty(), "Duplicate migration versions in " + vendor + ": " + duplicates);
        }
    }

    @Test
    void vendorsShareTheSameVersions() throws IOException {
        Set<String> h2 = versions("h2");
        Set<String> postgresql = versions("postgresql");
        h2.removeAll(VENDOR_SPECIFIC_VERSIONS);
        postgresql.removeAll(VENDOR_SPECIFIC_VERSIONS);
        Set<String> missingInPostgresql = new TreeSet<>(h2);
        missingInPostgresql.removeAll(postgresql);
        Set<String> missingInH2 = new TreeSet<>(postgresql);
        missingInH2.removeAll(h2);
        assertTrue(missingInPostgresql.isEmpty() && missingInH2.isEmpty(),
                "Every migration must exist for both vendors with the same version. Missing in postgresql: "
                        + missingInPostgresql + ", missing in h2: " + missingInH2);
    }

    private static String validate(String file) {
        Matcher timestamp = TIMESTAMP.matcher(file);
        if (timestamp.matches()) {
            try {
                LocalDateTime.parse(timestamp.group(1), TIMESTAMP_FORMAT);
                return null;
            } catch (DateTimeParseException e) {
                return "version is not a valid yyyy_MM_dd_HHmm timestamp";
            }
        }
        Matcher sequential = SEQUENTIAL.matcher(file);
        if (sequential.matches()) {
            int version = Integer.parseInt(sequential.group(1));
            return version <= LAST_SEQUENTIAL_VERSION
                    ? null
                    : "new migrations must use a timestamp version, e.g. V2026_10_20_1430__add_foo.sql";
        }
        return "expected V<yyyy>_<MM>_<dd>_<HHmm>__<lower_snake_description>.sql";
    }

    /** Version as Flyway sees it, with {@code _} normalised to {@code .}. */
    private static String version(String file) {
        return file.substring(1, file.indexOf("__")).replace('_', '.');
    }

    private static Set<String> versions(String vendor) throws IOException {
        Set<String> versions = new TreeSet<>();
        for (String file : migrationFiles(vendor)) {
            versions.add(version(file));
        }
        return versions;
    }

    private static List<String> migrationFiles(String vendor) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_ROOT.resolve(vendor))) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
