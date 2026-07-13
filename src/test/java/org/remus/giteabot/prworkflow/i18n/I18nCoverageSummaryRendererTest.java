package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class I18nCoverageSummaryRendererTest {

    @Test
    void renderStarting_listsPatternsBaselineAndLifecycle() {
        String out = I18nCoverageSummaryRenderer.renderStarting(
                7L, List.of("i18n/*.json"), "en", SuiteLifecycleMode.COMMIT_TO_PR);
        assertThat(out).contains("PR #7")
                .contains("`i18n/*.json`")
                .contains("`en`")
                .contains("commit-to-pr");
    }

    @Test
    void renderCompletion_listsChangedFiles(@TempDir Path ws) {
        I18nCoverageToolContext ctx = new I18nCoverageToolContext(ws, List.of("i18n/*.json"));
        ctx.recordCreated("i18n/de.json");
        ctx.recordUpdated("i18n/fr.json");
        ctx.recordDeleted("i18n/obsolete.json");

        String out = I18nCoverageSummaryRenderer.renderCompletion(
                7L, ctx, null, true, "and committed to `feature/x`", null);

        assertThat(out).contains("i18n/de.json")
                .contains("i18n/fr.json")
                .contains("i18n/obsolete.json")
                .contains("Added")
                .contains("Updated")
                .contains("Deleted");
    }

    @Test
    void renderCompletion_noChanges() {
        String out = I18nCoverageSummaryRenderer.renderCompletion(
                7L, null, null, false, null, "All locale files in sync.");
        assertThat(out).contains("No translation changes were needed")
                .contains("All locale files in sync.");
    }

    @Test
    void renderSkippedAndFailed() {
        assertThat(I18nCoverageSummaryRenderer.renderSkipped(7L, "no patterns"))
                .contains("Skipped").contains("no patterns");
        assertThat(I18nCoverageSummaryRenderer.renderFailed(7L, "boom"))
                .contains("❌").contains("boom");
    }
}
