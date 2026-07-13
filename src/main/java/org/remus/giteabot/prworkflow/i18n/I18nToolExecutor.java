package org.remus.giteabot.prworkflow.i18n;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches the two tools the {@link I18nCoverageAgent} uses to keep i18n
 * translation coverage in sync: {@code i18n-write} (create / update a locale
 * file with the drafted translations) and {@code i18n-delete} (remove an
 * obsolete locale file), both applied directly into the repository checkout.
 *
 * <p>Three safety layers protect the checkout (mirroring
 * {@code ReadmeSyncToolExecutor}):</p>
 * <ol>
 *   <li><b>Sandbox</b> — the resolved path must stay inside the checkout (no
 *       absolute paths, no {@code ..} traversal, no symlinks).</li>
 *   <li><b>i18n-scope guard</b> — the path must be a supported i18n file
 *       ({@code .properties} / {@code .json}) AND match one of the
 *       operator-configured include globs per {@link I18nPathGuard}.</li>
 *   <li><b>Pre-commit guard</b> — {@code I18nCoverageService} re-checks every
 *       changed file against the same {@link I18nPathGuard} before pushing.</li>
 * </ol>
 *
 * <p>Successful results start with {@code "OK:"}; validation / I/O problems
 * surface as a textual result starting with {@code "ERROR: "}.</p>
 */
@Slf4j
@Component
public class I18nToolExecutor {

    public String execute(String toolName, Map<String, Object> args, I18nCoverageToolContext ctx) {
        if (toolName == null) {
            return "ERROR: tool name is null";
        }
        if (ctx == null) {
            return "ERROR: I18nCoverageToolContext is null";
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        try {
            return switch (toolName.toLowerCase(Locale.ROOT).trim()) {
                case "i18n-write" -> i18nWrite(safeArgs, ctx);
                case "i18n-delete" -> i18nDelete(safeArgs, ctx);
                default -> "ERROR: unknown i18n-coverage tool '" + toolName + "'";
            };
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("i18n-coverage tool '{}' threw: {}", toolName, e.getMessage(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    String i18nWrite(Map<String, Object> args, I18nCoverageToolContext ctx) {
        String path = requireString(args, "path");
        String content = requireString(args, "content");
        String normalized = path.replace('\\', '/');

        if (!I18nPathGuard.isAllowedI18nPath(ctx.includePatterns(), normalized)) {
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
            throw new RuntimeException("Failed to write i18n file '" + path + "': " + e.getMessage(), e);
        }

        if (existed) {
            ctx.recordUpdated(normalized);
        } else {
            ctx.recordCreated(normalized);
        }
        return "OK: " + (existed ? "updated " : "created ") + normalized
                + " (" + content.length() + " bytes)";
    }

    String i18nDelete(Map<String, Object> args, I18nCoverageToolContext ctx) {
        String path = requireString(args, "path");
        String normalized = path.replace('\\', '/');

        if (!I18nPathGuard.isAllowedI18nPath(ctx.includePatterns(), normalized)) {
            return scopeRejection(normalized, ctx);
        }

        Path target = resolveInsideWorkspace(ctx.workspace(), normalized);
        if (!Files.exists(target)) {
            return "ERROR: cannot delete '" + normalized + "' — no such file in the checkout";
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete i18n file '" + path + "': " + e.getMessage(), e);
        }
        ctx.recordDeleted(normalized);
        return "OK: deleted " + normalized;
    }

    private static String scopeRejection(String path, I18nCoverageToolContext ctx) {
        return "ERROR: path '" + path + "' is outside the configured i18n scope. "
                + "This workflow may only create/update/delete locale files (*.properties / *.json) "
                + "matching the configured include patterns " + ctx.includePatterns()
                + " — production code and other files are off-limits.";
    }

    /**
     * Resolves a caller-supplied relative path inside the checkout, rejecting
     * any value that would escape it (absolute paths, {@code ..} traversal,
     * symlink trickery). Mirrors {@code ReadmeSyncToolExecutor.resolveInsideWorkspace}.
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
