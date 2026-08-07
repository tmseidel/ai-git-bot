package org.remus.giteabot.prworkflow.unittest.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.unittest.UnitTestCase;
import org.remus.giteabot.prworkflow.unittest.UnitTestCaseRepository;
import org.remus.giteabot.prworkflow.unittest.UnitTestCaseStatus;
import org.remus.giteabot.prworkflow.unittest.UnitTestPathGuard;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches the single {@code unit-test-write} tool the author agent uses to
 * materialise a generated unit-test file directly into the repository
 * checkout.
 *
 * <p>Three safety layers protect the checkout:</p>
 * <ol>
 *   <li><b>Sandbox</b> — the resolved path must stay inside the checkout
 *       (no absolute paths, no {@code ..} traversal, no symlinks).</li>
 *   <li><b>Test-location guard</b> — the path must resolve to a legitimate
 *       test location for the framework per
 *       {@link org.remus.giteabot.prworkflow.unittest.UnitTestPathGuard}
 *       (dedicated test root, {@code __tests__}-style segment, or a test
 *       filename convention such as {@code *_test.go}). Bare production
 *       source roots like {@code src/} are rejected, so the agent can only
 *       add tests, never overwrite application code.</li>
 *   <li><b>Pre-commit guard</b> — the executor only ever writes; before the
 *       generated tests are pushed, {@code UnitTestService} re-checks every
 *       changed file against the same {@code UnitTestPathGuard} and aborts the
 *       commit if anything outside a test location was touched.</li>
 * </ol>
 */
@Slf4j
@Component
public class UnitTestToolExecutor {

    private final UnitTestCaseRepository caseRepository;

    public UnitTestToolExecutor(UnitTestCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    /**
     * Executes the named tool. Unknown tools, validation errors and I/O
     * problems all surface as a textual result starting with {@code "ERROR: "};
     * successful writes start with {@code "OK:"}.
     */
    public String execute(String toolName, Map<String, Object> args, UnitTestToolContext ctx) {
        if (toolName == null) {
            return "ERROR: tool name is null";
        }
        if (ctx == null) {
            return "ERROR: UnitTestToolContext is null";
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        try {
            if ("unit-test-write".equals(toolName.toLowerCase(Locale.ROOT).trim())) {
                return unitTestWrite(safeArgs, ctx);
            }
            return "ERROR: unknown unit-test tool '" + toolName + "'";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("unit-test tool '{}' threw: {}", toolName, e.getMessage(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    String unitTestWrite(Map<String, Object> args, UnitTestToolContext ctx) {
        String path = requireString(args, "path");
        String content = requireString(args, "content");
        String title = optString(args, "title");

        String normalized = path.replace('\\', '/');
        if (!UnitTestPathGuard.isAllowedTestPath(ctx.framework(), normalized)) {
            return "ERROR: path '" + path + "' is not an allowed test location for "
                    + ctx.framework().key() + " (allowed test roots: "
                    + ctx.framework().allowedTestPrefixes() + ", segments: "
                    + ctx.framework().allowedTestPathSegments() + ", filename suffixes: "
                    + ctx.framework().allowedTestFileSuffixes() + ") — the agent may only write "
                    + "tests, never production code";
        }

        Path target = resolveInsideWorkspace(ctx.workspace(), normalized);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test file '" + path + "': " + e.getMessage(), e);
        }

        UnitTestCase persisted = caseRepository.findBySuiteAndPath(ctx.suite(), normalized)
                .map(existing -> {
                    existing.setContent(content);
                    if (title != null) {
                        existing.setTitle(title);
                    }
                    existing.setLastStatus(UnitTestCaseStatus.PENDING);
                    existing.setLastLog(null);
                    existing.setLastRunAt(null);
                    existing.setLastDurationMs(null);
                    return caseRepository.save(existing);
                })
                .orElseGet(() -> {
                    UnitTestCase fresh = new UnitTestCase();
                    fresh.setSuite(ctx.suite());
                    fresh.setPath(normalized);
                    fresh.setTitle(title);
                    fresh.setContent(content);
                    fresh.setLastStatus(UnitTestCaseStatus.PENDING);
                    return caseRepository.save(fresh);
                });

        return "OK: wrote " + content.length() + " bytes to " + normalized
                + " (UnitTestCase id=" + persisted.getId() + ")";
    }


    /**
     * Resolves a caller-supplied relative path inside the checkout, rejecting
     * any value that would escape it (absolute paths, {@code ..} traversal,
     * symlink trickery). Mirrors {@code PrTestWorkspaceManager.resolveInsideWorkspace}.
     */
    static Path resolveInsideWorkspace(Path workspace, String relativePath) {
        return org.remus.giteabot.util.WorkspacePaths.resolveInsideWorkspace(workspace, relativePath);
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("Missing required argument '" + key + "'");
        }
        String value = raw.toString();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Required argument '" + key + "' must not be empty");
        }
        return value;
    }

    private static String optString(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        return value.isBlank() ? null : value;
    }
}

