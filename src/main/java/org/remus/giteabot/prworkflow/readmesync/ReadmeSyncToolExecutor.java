package org.remus.giteabot.prworkflow.readmesync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches the two tools the {@link ReadmeSyncAgent} uses to keep project
 * documentation in sync with the code: {@code doc-write} (create / update a
 * Markdown file) and {@code doc-delete} (remove an obsolete Markdown file),
 * both applied directly into the repository checkout.
 *
 * <p>Three safety layers protect the checkout (mirroring
 * {@code UnitTestToolExecutor}):</p>
 * <ol>
 *   <li><b>Sandbox</b> — the resolved path must stay inside the checkout (no
 *       absolute paths, no {@code ..} traversal, no symlinks).</li>
 *   <li><b>Doc-scope guard</b> — the path must be Markdown AND match one of the
 *       operator-configured include globs per {@link DocPathGuard}. Anything
 *       outside the configured documentation scope, or any non-Markdown file,
 *       is rejected.</li>
 *   <li><b>Pre-commit guard</b> — {@code ReadmeSyncService} re-checks every
 *       changed file against the same {@link DocPathGuard} before pushing and
 *       aborts if anything outside the documentation scope was touched.</li>
 * </ol>
 *
 * <p>Successful results start with {@code "OK:"}; validation / I/O problems
 * surface as a textual result starting with {@code "ERROR: "}.</p>
 */
@Slf4j
@Component
public class ReadmeSyncToolExecutor {

    public String execute(String toolName, Map<String, Object> args, ReadmeSyncToolContext ctx) {
        if (toolName == null) {
            return "ERROR: tool name is null";
        }
        if (ctx == null) {
            return "ERROR: ReadmeSyncToolContext is null";
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        try {
            return switch (toolName.toLowerCase(Locale.ROOT).trim()) {
                case "doc-write" -> docWrite(safeArgs, ctx);
                case "doc-delete" -> docDelete(safeArgs, ctx);
                default -> "ERROR: unknown readme-sync tool '" + toolName + "'";
            };
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("readme-sync tool '{}' threw: {}", toolName, e.getMessage(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    String docWrite(Map<String, Object> args, ReadmeSyncToolContext ctx) {
        String path = requireString(args, "path");
        String content = requireString(args, "content");
        String normalized = path.replace('\\', '/');

        if (!DocPathGuard.isAllowedDocPath(ctx.includePatterns(), normalized)) {
            return scopeRejection(normalized, ctx);
        }

        Path target = resolveInsideWorkspace(ctx.workspace(), normalized);
        boolean existed = Files.exists(target);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write documentation file '" + path + "': " + e.getMessage(), e);
        }

        if (existed) {
            ctx.recordUpdated(normalized);
        } else {
            ctx.recordCreated(normalized);
        }
        return "OK: " + (existed ? "updated " : "created ") + normalized
                + " (" + content.length() + " bytes)";
    }

    String docDelete(Map<String, Object> args, ReadmeSyncToolContext ctx) {
        String path = requireString(args, "path");
        String normalized = path.replace('\\', '/');

        if (!DocPathGuard.isAllowedDocPath(ctx.includePatterns(), normalized)) {
            return scopeRejection(normalized, ctx);
        }

        Path target = resolveInsideWorkspace(ctx.workspace(), normalized);
        if (!Files.exists(target)) {
            return "ERROR: cannot delete '" + normalized + "' — no such file in the checkout";
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete documentation file '" + path + "': " + e.getMessage(), e);
        }
        ctx.recordDeleted(normalized);
        return "OK: deleted " + normalized;
    }

    private static String scopeRejection(String path, ReadmeSyncToolContext ctx) {
        return "ERROR: path '" + path + "' is outside the configured documentation scope. "
                + "This workflow may only create/update/delete Markdown files matching the "
                + "configured include patterns " + ctx.includePatterns()
                + " — production code and non-Markdown files are off-limits.";
    }

    /**
     * Resolves a caller-supplied relative path inside the checkout, rejecting
     * any value that would escape it (absolute paths, {@code ..} traversal,
     * symlink trickery). Mirrors {@code UnitTestToolExecutor.resolveInsideWorkspace}.
     */
    static Path resolveInsideWorkspace(Path workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path candidate = workspace.resolve(relativePath).toAbsolutePath().normalize();
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!candidate.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' escapes the workspace");
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' resolves to a symlink");
        }
        return candidate;
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
}
