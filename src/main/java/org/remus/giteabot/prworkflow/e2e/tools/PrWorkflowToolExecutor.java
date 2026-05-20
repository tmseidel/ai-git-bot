package org.remus.giteabot.prworkflow.e2e.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseStatus;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dispatches the five {@code PR_WORKFLOW} tools shipped in M4 wave 2:
 *
 * <ul>
 *     <li>{@code pr-test-write}  — persists a generated test file inside the
 *         sandboxed workspace and upserts the matching
 *         {@link PrTestCase} row.</li>
 *     <li>{@code pr-test-run}    — executes the chosen test framework against
 *         the preview deployment; returns a structured summary. For Playwright
 *         the runner parses the JSON reporter output and updates per-case
 *         {@link PrTestCaseStatus} / duration / log.</li>
 *     <li>{@code preview-url}    — returns the deployment's preview URL.</li>
 *     <li>{@code preview-status} — HTTP-probes the preview URL with optional
 *         path / expected-status; reports outcome textually.</li>
 *     <li>{@code attach-artifact} — uploads a workspace-relative file via
 *         {@link org.remus.giteabot.repository.RepositoryApiClient#attachPullRequestArtifact(
 *         String, String, Long, String, String, byte[])}.</li>
 * </ul>
 *
 * <p>All tools are workspace-sandboxed via
 * {@link PrTestWorkspaceManager#resolveInsideWorkspace(Path, String)} — path
 * traversal, absolute paths and symlinks are rejected with a clear textual
 * error that is returned to the agent as the tool result.</p>
 *
 * <p>The executor is intentionally I/O-bounded but free of Spring scopes
 * beyond {@link Component}: every invocation takes the full
 * {@link PrWorkflowToolContext}, so the same singleton bean can serve any
 * number of concurrent PR workflow runs.</p>
 */
@Slf4j
@Component
public class PrWorkflowToolExecutor {

    /** Default cap for {@code pr-test-run} per-invocation runtime. */
    public static final long DEFAULT_RUN_TIMEOUT_MS = 5L * 60L * 1000L;
    /** Hard cap for captured stdout/stderr per {@code pr-test-run}. */
    public static final int  DEFAULT_RUN_OUTPUT_BYTES = 256 * 1024;
    /** Hard cap on artifact size we are willing to load into memory. */
    public static final int  ATTACH_ARTIFACT_MAX_BYTES = 4 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrTestCaseRepository caseRepository;
    private final PrTestWorkspaceManager workspaceManager;
    private final WorkspaceProcessRunner processRunner;
    private final PreviewHttpProbe httpProbe;

    public PrWorkflowToolExecutor(PrTestCaseRepository caseRepository,
                                  PrTestWorkspaceManager workspaceManager,
                                  WorkspaceProcessRunner processRunner,
                                  PreviewHttpProbe httpProbe) {
        this.caseRepository = caseRepository;
        this.workspaceManager = workspaceManager;
        this.processRunner = processRunner;
        this.httpProbe = httpProbe;
    }

    // ----------------------------------------------------------------- dispatch

    /**
     * Executes the named tool. Unknown tools, validation errors and I/O
     * problems all surface as a non-empty textual result starting with
     * {@code "ERROR: "}; the caller is expected to forward the result to the
     * agent unchanged.
     */
    public String execute(String toolName, Map<String, Object> args, PrWorkflowToolContext ctx) {
        if (toolName == null) {
            return "ERROR: tool name is null";
        }
        if (ctx == null) {
            return "ERROR: PrWorkflowToolContext is null";
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        try {
            return switch (toolName.toLowerCase(Locale.ROOT).trim()) {
                case "pr-test-write"   -> prTestWrite(safeArgs, ctx);
                case "pr-test-run"     -> prTestRun(safeArgs, ctx);
                case "preview-url"     -> previewUrl(ctx);
                case "preview-status"  -> previewStatus(safeArgs, ctx);
                case "attach-artifact" -> attachArtifact(safeArgs, ctx);
                default -> "ERROR: unknown PR-workflow tool '" + toolName + "'";
            };
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("PR-workflow tool '{}' threw: {}", toolName, e.getMessage(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ----------------------------------------------------------------- pr-test-write

    String prTestWrite(Map<String, Object> args, PrWorkflowToolContext ctx) {
        String path = requireString(args, "path");
        String content = requireString(args, "content");
        String title = optString(args, "title");

        Path target = workspaceManager.resolveInsideWorkspace(ctx.workspace(), path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test file '" + path + "': " + e.getMessage(), e);
        }

        PrTestCase persisted = caseRepository.findBySuiteAndPath(ctx.suite(), path)
                .map(existing -> {
                    existing.setContent(content);
                    if (title != null) existing.setTitle(title);
                    existing.setLastStatus(PrTestCaseStatus.PENDING);
                    existing.setLastLog(null);
                    existing.setLastRunAt(null);
                    existing.setLastDurationMs(null);
                    return caseRepository.save(existing);
                })
                .orElseGet(() -> {
                    PrTestCase fresh = new PrTestCase();
                    fresh.setSuite(ctx.suite());
                    fresh.setPath(path);
                    fresh.setTitle(title);
                    fresh.setContent(content);
                    fresh.setLastStatus(PrTestCaseStatus.PENDING);
                    return caseRepository.save(fresh);
                });

        return "OK: wrote " + content.length() + " bytes to " + path
                + " (PrTestCase id=" + persisted.getId() + ")";
    }

    // ----------------------------------------------------------------- pr-test-run

    String prTestRun(Map<String, Object> args, PrWorkflowToolContext ctx) {
        String frameworkArg = requireString(args, "framework");
        E2eTestFramework framework;
        try {
            framework = E2eTestFramework.fromKey(frameworkArg);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown framework '" + frameworkArg + "'");
        }
        if (framework != ctx.framework()) {
            throw new IllegalArgumentException(
                    "Framework mismatch — workspace was scaffolded for "
                            + ctx.framework().key() + " but tool was called with " + frameworkArg);
        }
        List<String> rawArgs = stringList(args.get("args"));
        List<String> command = buildCommand(framework, rawArgs);

        WorkspaceProcessRunner.ProcessResult result;
        try {
            result = processRunner.run(ctx.workspace(), command,
                    DEFAULT_RUN_TIMEOUT_MS, DEFAULT_RUN_OUTPUT_BYTES);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to execute " + framework.key() + " runner: " + e.getMessage(), e);
        }

        // Best-effort per-case status update for Playwright JSON reporter output.
        int updated = 0;
        if (framework == E2eTestFramework.PLAYWRIGHT) {
            updated = updatePlaywrightCases(ctx, result);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(result.timedOut() ? "TIMEOUT" : ("exit=" + result.exitCode()))
          .append(" duration=").append(result.durationMs()).append("ms");
        if (updated > 0) {
            sb.append(" updatedCases=").append(updated);
        }
        sb.append("\ncommand: ").append(String.join(" ", command)).append('\n');
        sb.append("--- output (truncated to ").append(DEFAULT_RUN_OUTPUT_BYTES).append(" bytes) ---\n");
        sb.append(result.combinedOutput());
        return sb.toString();
    }

    private List<String> buildCommand(E2eTestFramework framework, List<String> extra) {
        List<String> cmd = new ArrayList<>();
        switch (framework) {
            case PLAYWRIGHT -> {
                cmd.add("npx");
                cmd.add("--yes");
                cmd.add("playwright");
                cmd.add("test");
                cmd.add("--reporter=json");
            }
            case CYPRESS -> {
                cmd.add("npx");
                cmd.add("--yes");
                cmd.add("cypress");
                cmd.add("run");
            }
            case PYTEST -> {
                cmd.add("pytest");
                cmd.add("-q");
            }
            case K6 -> {
                cmd.add("k6");
                cmd.add("run");
            }
        }
        cmd.addAll(extra);
        return cmd;
    }

    private int updatePlaywrightCases(PrWorkflowToolContext ctx, WorkspaceProcessRunner.ProcessResult result) {
        // Playwright with the JSON reporter prints the report as a single JSON
        // object to stdout. We extract the first balanced { ... } block.
        String json = extractFirstJsonObject(result.combinedOutput());
        if (json == null) {
            return 0;
        }
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode suites = root.get("suites");
            if (suites == null || !suites.isArray()) {
                return 0;
            }
            Instant now = Instant.now();
            int updated = 0;
            for (JsonNode suite : suites) {
                updated += walkPlaywrightSuite(ctx, suite, now);
            }
            return updated;
        } catch (Exception e) {
            log.debug("Failed to parse Playwright JSON report: {}", e.getMessage());
            return 0;
        }
    }

    private int walkPlaywrightSuite(PrWorkflowToolContext ctx, JsonNode suite, Instant now) {
        int updated = 0;
        JsonNode file = suite.get("file");
        JsonNode specs = suite.get("specs");
        if (specs != null && specs.isArray() && file != null) {
            String relativePath = file.asString();
            Optional<PrTestCase> caseOpt = caseRepository.findBySuiteAndPath(ctx.suite(), relativePath);
            if (caseOpt.isPresent()) {
                PrTestCase pc = caseOpt.get();
                PlaywrightSpecOutcome rollup = rollupSpecs(specs);
                pc.setLastStatus(rollup.status());
                pc.setLastRunAt(now);
                pc.setLastDurationMs(rollup.durationMs());
                pc.setLastLog(truncate(rollup.log(), 8 * 1024));
                caseRepository.save(pc);
                updated++;
            }
        }
        JsonNode nested = suite.get("suites");
        if (nested != null && nested.isArray()) {
            for (JsonNode child : nested) {
                updated += walkPlaywrightSuite(ctx, child, now);
            }
        }
        return updated;
    }

    private record PlaywrightSpecOutcome(PrTestCaseStatus status, long durationMs, String log) { }

    private PlaywrightSpecOutcome rollupSpecs(JsonNode specs) {
        long totalDuration = 0;
        boolean anyFail = false;
        boolean anyFlaky = false;
        boolean anyPass = false;
        StringBuilder log = new StringBuilder();
        for (JsonNode spec : specs) {
            JsonNode tests = spec.get("tests");
            if (tests == null || !tests.isArray()) continue;
            for (JsonNode test : tests) {
                JsonNode results = test.get("results");
                if (results == null || !results.isArray()) continue;
                boolean passed = false;
                boolean failed = false;
                for (JsonNode r : results) {
                    String status = r.path("status").asString("");
                    long durMs = r.path("duration").asLong(0);
                    totalDuration += durMs;
                    if ("passed".equals(status) || "expected".equals(status)) passed = true;
                    if ("failed".equals(status) || "timedOut".equals(status) || "unexpected".equals(status)) {
                        failed = true;
                        JsonNode err = r.get("error");
                        if (err != null) {
                            log.append("- ").append(err.path("message").asString("(no message)")).append('\n');
                        }
                    }
                }
                if (failed && passed) anyFlaky = true;
                else if (failed) anyFail = true;
                else if (passed) anyPass = true;
            }
        }
        PrTestCaseStatus status;
        if (anyFail) status = PrTestCaseStatus.FAILED;
        else if (anyFlaky) status = PrTestCaseStatus.FLAKY;
        else if (anyPass) status = PrTestCaseStatus.PASSED;
        else status = PrTestCaseStatus.SKIPPED;
        return new PlaywrightSpecOutcome(status, totalDuration, log.toString());
    }

    private static String extractFirstJsonObject(String haystack) {
        if (haystack == null) return null;
        int start = haystack.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < haystack.length(); i++) {
            char c = haystack.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return haystack.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
    }

    // ----------------------------------------------------------------- preview-url

    String previewUrl(PrWorkflowToolContext ctx) {
        String url = ctx.previewUrl();
        return url == null || url.isBlank()
                ? "ERROR: no preview URL is set on the workflow context"
                : url;
    }

    // ----------------------------------------------------------------- preview-status

    String previewStatus(Map<String, Object> args, PrWorkflowToolContext ctx) {
        if (ctx.previewUrl() == null || ctx.previewUrl().isBlank()) {
            return "ERROR: no preview URL is set on the workflow context";
        }
        String path = optString(args, "path");
        int expected = optInt(args, "expectedStatus", 200);
        String url = appendPath(ctx.previewUrl(), path);
        PreviewHttpProbe.ProbeResult probe = httpProbe.probe(url);
        boolean ok = probe.statusCode() == expected;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("url", url);
        response.put("status", probe.statusCode());
        response.put("expected", expected);
        response.put("ok", ok);
        response.put("durationMs", probe.durationMs());
        response.put("bodyExcerpt", probe.bodyExcerpt());
        try {
            return JSON.writeValueAsString(response);
        } catch (RuntimeException e) {
            return "url=" + url + " status=" + probe.statusCode() + " ok=" + ok
                    + " durationMs=" + probe.durationMs();
        }
    }

    private static String appendPath(String base, String path) {
        if (path == null || path.isBlank()) return base;
        String trimmedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String trimmedPath = path.startsWith("/") ? path : "/" + path;
        return trimmedBase + trimmedPath;
    }

    // ----------------------------------------------------------------- attach-artifact

    String attachArtifact(Map<String, Object> args, PrWorkflowToolContext ctx) {
        String path = requireString(args, "path");
        String title = optString(args, "title");
        Path target = workspaceManager.resolveInsideWorkspace(ctx.workspace(), path);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Artifact '" + path + "' does not exist or is not a regular file");
        }
        long size;
        byte[] payload;
        try {
            size = Files.size(target);
            if (size > ATTACH_ARTIFACT_MAX_BYTES) {
                throw new IllegalArgumentException(
                        "Artifact '" + path + "' is " + size + " bytes — exceeds the "
                                + ATTACH_ARTIFACT_MAX_BYTES + "-byte cap");
            }
            payload = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read artifact '" + path + "': " + e.getMessage(), e);
        }
        String name = title != null && !title.isBlank() ? title : target.getFileName().toString();
        String contentType = guessContentType(target, name);
        if (ctx.apiClient() == null || ctx.prNumber() == null) {
            return "ERROR: cannot attach artifact — RepositoryApiClient / pr number missing on context";
        }
        ctx.apiClient().attachPullRequestArtifact(
                ctx.owner(), ctx.repo(), ctx.prNumber(), name, contentType, payload);
        return "OK: attached '" + name + "' (" + size + " bytes, contentType="
                + (contentType == null ? "n/a" : contentType) + ")";
    }

    private static String guessContentType(Path file, String displayName) {
        try {
            String mime = Files.probeContentType(file);
            if (mime != null) return mime;
        } catch (IOException ignored) {
            // fallthrough
        }
        String fallback = URLConnection.guessContentTypeFromName(displayName);
        return fallback;
    }

    // ----------------------------------------------------------------- arg helpers

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
        if (raw == null) return null;
        String value = raw.toString();
        return value.isBlank() ? null : value;
    }

    private static int optInt(Map<String, Object> args, String key, int fallback) {
        Object raw = args.get(key);
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) out.add(item.toString());
            }
            return out;
        }
        if (raw instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        throw new IllegalArgumentException(
                "Argument 'args' must be a JSON array of strings, got " + raw.getClass().getSimpleName());
    }
}
