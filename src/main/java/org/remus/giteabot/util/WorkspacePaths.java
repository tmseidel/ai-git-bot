package org.remus.giteabot.util;

import java.io.IOException;
import java.nio.file.Files;
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
        Path base = workspace.toAbsolutePath().normalize();
        Path candidate = base.resolve(relativePath).normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException("Path '" + relativePath + "' escapes the workspace");
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
            while (existing != null && !Files.exists(existing)) {
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
