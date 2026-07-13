package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadmeSyncSummaryRendererTest {

    private ReadmeSyncToolContext ctx() {
        return new ReadmeSyncToolContext(Path.of("/tmp/ws"), List.of("README.md"));
    }

    @Test
    void completion_noChanges_includesExplanation() {
        String md = ReadmeSyncSummaryRenderer.renderCompletion(
                42, ctx(), false, null, "DONE — documentation already accurate.");
        assertTrue(md.contains("No documentation changes were needed"), md);
        assertTrue(md.contains("documentation already accurate"), md);
    }

    @Test
    void completion_listsAddedUpdatedDeleted() {
        ReadmeSyncToolContext ctx = ctx();
        ctx.recordCreated("doc/new.md");
        ctx.recordUpdated("README.md");
        ctx.recordDeleted("doc/old.md");

        String md = ReadmeSyncSummaryRenderer.renderCompletion(
                7, ctx, true, "and committed to `feature`", null);

        assertTrue(md.contains("Documentation was updated"), md);
        assertTrue(md.contains("`doc/new.md`"), md);
        assertTrue(md.contains("`README.md`"), md);
        assertTrue(md.contains("`doc/old.md`"), md);
        assertTrue(md.contains("Added"), md);
        assertTrue(md.contains("Updated"), md);
        assertTrue(md.contains("Deleted"), md);
    }

    @Test
    void completion_uncommittedShowsWarning() {
        ReadmeSyncToolContext ctx = ctx();
        ctx.recordUpdated("README.md");
        String md = ReadmeSyncSummaryRenderer.renderCompletion(1, ctx, false, null, null);
        assertTrue(md.contains("could not be committed"), md);
    }

    @Test
    void starting_listsPatternsAndLifecycle() {
        String md = ReadmeSyncSummaryRenderer.renderStarting(
                3, List.of("README.md", "doc/**/*.md"), SuiteLifecycleMode.COMMIT_TO_PR);
        assertTrue(md.contains("`README.md`"), md);
        assertTrue(md.contains("`doc/**/*.md`"), md);
        assertTrue(md.contains("commit-to-pr"), md);
    }

    @Test
    void skipped_and_failed_areDistinct() {
        assertTrue(ReadmeSyncSummaryRenderer.renderSkipped(1, "no diff").contains("Skipped"));
        String failed = ReadmeSyncSummaryRenderer.renderFailed(1, "boom");
        assertTrue(failed.contains("❌"), failed);
        assertFalse(failed.contains("Skipped"), failed);
    }
}
