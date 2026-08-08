package org.remus.giteabot.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Central workspace path guard for agent-facing file tools.
 *
 * <p>Resolves a caller-supplied relative path against the workspace root and
 * rejects anything that would escape it - including {@code ..} traversal,
 * absolute paths, and symlink tricks <em>in intermediate directories</em>
 * (which a pull request can commit). The naive leaf-only {@code isSymbolicLink}
 * check misses directory symlinks, so the nearest existing ancestor is
 * resolved against its real path instead.</p>
 *
 * <p><strong>Deliberately strict on {@code ..}:</strong> any path containing a
 * {@code ..} segment is rejected up front, even when normalizing it would stay
 * inside the workspace (e.g. {@code src/../inside.txt}). Tool contracts should
 * always pass resolved {@code absolute/normalized} paths or plain relative
 * paths without parent references; callers seeing a "traversal" error for a
 * semantically in-bounds path should fix the caller, not loosen this guard.</p>
 */
public final class WorkspacePaths {

    private WorkspacePaths() {
    }

    /**
     * Resolves {@code relativePath} inside {@code workspace}, rejecting escapes.
     *
     * @return the normalized absolute candidate path (may not exist yet)
     * @throws IllegalArgumentException if the path is blank or escapes the workspace
     */
    public static Path resolveInsideWorkspace(Path workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path requestedPath = Path.of(relativePath);
        if (requestedPath.isAbsolute()) {
            throw new IllegalArgumentException("Path must be relative: " + relativePath);
        }
        for (Path segment : requestedPath) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException(
                        "Path '" + relativePath + "' escapes the workspace via traversal");
            }
        }
        Path base = workspace.toAbsolutePath().normalize();
        Path candidate = base.resolve(requestedPath).normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' escapes the workspace");
        }
        for (Path segment : candidate) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                throw new IllegalArgumentException("Access to .git internals is not allowed: " + relativePath);
            }
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' resolves to a symlink");
        }
        // Symlink check on the nearest existing ancestor: catches directory
        // symlinks (e.g. "docs -> /etc") committed by the PR, which the leaf
        // check above cannot see when the target file does not exist yet.
        try {
            Path realBase = base.toRealPath();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realBase)) {
                throw new IllegalArgumentException(
                        "Path '" + relativePath + "' escapes the workspace via symlinked directory");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot verify path '" + relativePath + "'", e);
        }
        return candidate;
    }
}
