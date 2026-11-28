package org.remus.giteabot.reviewui;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.knowledge.LessonRejectedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The small rules of the Reviews pages that need no database. */
class ReviewUiUnitTest {

    @Test
    void returnTargetsStayOnTheReviewsPages() {
        assertThat(ReviewUiController.safeReturn("/reviews/feedback?page=2", "/x")).isEqualTo("/reviews/feedback?page=2");
        assertThat(ReviewUiController.safeReturn("//evil.example/reviews", "/x")).isEqualTo("/x");
        assertThat(ReviewUiController.safeReturn("https://evil.example/reviews", "/x")).isEqualTo("/x");
        assertThat(ReviewUiController.safeReturn("/settings", "/x")).isEqualTo("/x");
        assertThat(ReviewUiController.safeReturn("/reviews\\evil", "/x")).isEqualTo("/x");
        assertThat(ReviewUiController.safeReturn("/reviews\r\nLocation: x", "/x")).isEqualTo("/x");
        assertThat(ReviewUiController.safeReturn(null, "/x")).isEqualTo("/x");
    }

    @Test
    void theAdminIsRecordedWithAPrefixThatNoGitAccountCarries() {
        assertThat(ReviewUiActionService.actorOf("alice")).isEqualTo("admin:alice");
        assertThat(ReviewUiActionService.actorOf(" ")).isEqualTo("admin:unknown");
        assertThat(ReviewUiActionService.actorOf("x".repeat(500))).hasSize(200);
    }

    @Test
    void aMalformedRequestIdIsReplacedNotTrusted() {
        assertThat(ReviewUiActionService.requestIdOf("3f2c9a40-0b1e-4c56-9d2f-1a2b3c4d5e6f"))
                .isEqualTo("3f2c9a40-0b1e-4c56-9d2f-1a2b3c4d5e6f");
        assertThat(ReviewUiActionService.requestIdOf("x' OR 1=1")).hasSize(36);
        assertThat(ReviewUiActionService.requestIdOf(null)).hasSize(36);
    }

    @Test
    void pathsUseThePathsLineRules() {
        assertThat(ReviewUiActionService.pathsOf("  ")).isEmpty();
        assertThat(ReviewUiActionService.pathsOf("**")).isEmpty();
        assertThat(ReviewUiActionService.pathsOf("src/** docs/*.md")).containsExactly("src/**", "docs/*.md");
        assertThatThrownBy(() -> ReviewUiActionService.pathsOf("../outside"))
                .isInstanceOf(LessonRejectedException.class);
    }

    @Test
    void anAdminUiSourceIsIdentifiableAndDistinctFromEveryComment() {
        FeedbackSource ui = FeedbackSource.adminUi(1L, 2L, "acme", "web", null, "req-12345678");
        FeedbackSource comment = new FeedbackSource(1L, 2L, "acme", "web", null,
                FeedbackSource.SubjectKind.PR, 0L, "PR_COMMENT", "req-12345678");
        assertThat(ui.isIdentifiable()).isTrue();
        assertThat(ui.isAdminUi()).isTrue();
        assertThat(comment.isAdminUi()).isFalse();
        assertThat(ui.sourceKey()).isNotEqualTo(comment.sourceKey())
                .isEqualTo(FeedbackSource.adminUi(1L, 2L, "ACME", "Web", null, "req-12345678").sourceKey());
    }

    @Test
    void filterLinksKeepTheOtherParametersAndStartOverOnPageOne() {
        ReviewUiFilterForm filter = new ReviewUiFilterForm();
        filter.setProject("acme/web app");
        filter.setBlockers(true);
        filter.setPage(3);
        assertThat(filter.owner()).isEqualTo("acme");
        assertThat(filter.repo()).isEqualTo("web app");
        assertThat(filter.with("verdict", "USEFUL")).isEqualTo("project=acme%2Fweb+app&blockers=true&verdict=USEFUL&size=20");
        assertThat(filter.with("page", 4)).isEqualTo("project=acme%2Fweb+app&blockers=true&page=4&size=20");
        assertThat(filter.paramsWithout("size", "page")).containsOnlyKeys("project", "blockers");
    }

    @Test
    void rowsPerPageAreBoundedLikeOnTheWorkflowRunsPage() {
        ReviewUiFilterForm filter = new ReviewUiFilterForm();
        assertThat(filter.effectiveSize()).isEqualTo(20);
        filter.setSize(50);
        assertThat(filter.effectiveSize()).isEqualTo(50);
        assertThat(filter.with("page", 2)).isEqualTo("page=2&size=50");
        filter.setSize(100_000);
        assertThat(filter.effectiveSize()).isEqualTo(ReviewUiFilterForm.MAX_PAGE_SIZE);
        filter.setSize(0);
        assertThat(filter.effectiveSize()).isEqualTo(ReviewUiFilterForm.DEFAULT_PAGE_SIZE);
        assertThat(filter.effectiveLessonState()).isEqualTo(ReviewUiFilterForm.ALL);
    }
}
