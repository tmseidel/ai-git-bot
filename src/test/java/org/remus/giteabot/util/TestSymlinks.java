package org.remus.giteabot.util;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates symlinks for tests, skipping the test where the OS does not allow it
 * (Windows without Developer Mode or administrator rights).
 */
public final class TestSymlinks {

    private TestSymlinks() {
    }

    public static Path createOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("Symbolic links are not available: " + e.getMessage());
            throw e;
        }
    }
}
