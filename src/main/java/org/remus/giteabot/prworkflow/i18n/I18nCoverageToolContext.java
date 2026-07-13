package org.remus.giteabot.prworkflow.i18n;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-invocation context the {@link I18nToolExecutor} needs to apply one i18n
 * file change into the repository checkout and to record which locale files the
 * agent touched.
 *
 * <p>Unlike the E2E / unit-test workflows there is no persisted suite entity —
 * the {@link I18nCoverageWorkflow} is deliberately DB-migration-free, so the set
 * of created / updated / deleted locale files is tracked in memory here and read
 * back by {@code I18nCoverageService} to build the completion comment and to
 * drive the suite-lifecycle promotion off the live workspace diff.</p>
 *
 * @param workspace       the repository checkout (PR head branch); writes are
 *                        sandboxed to stay inside it
 * @param includePatterns the operator-configured i18n include globs — define
 *                        both the detection input scope and the allowed output
 *                        scope
 */
public final class I18nCoverageToolContext {

    private final Path workspace;
    private final List<String> includePatterns;
    private final Set<String> created = new TreeSet<>();
    private final Set<String> updated = new TreeSet<>();
    private final Set<String> deleted = new TreeSet<>();

    public I18nCoverageToolContext(Path workspace, List<String> includePatterns) {
        this.workspace = workspace;
        this.includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
    }

    public Path workspace() {
        return workspace;
    }

    public List<String> includePatterns() {
        return includePatterns;
    }

    public void recordCreated(String path) {
        deleted.remove(path);
        created.add(path);
    }

    public void recordUpdated(String path) {
        deleted.remove(path);
        // A file created then updated in the same run stays "created".
        if (!created.contains(path)) {
            updated.add(path);
        }
    }

    public void recordDeleted(String path) {
        created.remove(path);
        updated.remove(path);
        deleted.add(path);
    }

    public Set<String> created() {
        return Set.copyOf(created);
    }

    public Set<String> updated() {
        return Set.copyOf(updated);
    }

    public Set<String> deleted() {
        return Set.copyOf(deleted);
    }

    /** {@code true} when the agent applied at least one i18n change. */
    public boolean touchedAnything() {
        return !created.isEmpty() || !updated.isEmpty() || !deleted.isEmpty();
    }

    public int changeCount() {
        return created.size() + updated.size() + deleted.size();
    }
}
