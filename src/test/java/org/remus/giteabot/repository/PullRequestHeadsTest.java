package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PullRequestHeads} — the rules the three provider head
 * lookups (Gitea, GitHub, GitLab) share.
 */
class PullRequestHeadsTest {

    @Test
    void normalizeBranch_stripsTheHeadsPrefix() {
        assertThat(PullRequestHeads.normalizeBranch("refs/heads/feature/login"))
                .isEqualTo("feature/login");
    }

    @Test
    void normalizeBranch_leavesABareBranchNameUntouched() {
        assertThat(PullRequestHeads.normalizeBranch("develop")).isEqualTo("develop");
    }

    @Test
    void normalizeBranch_doesNotStripTheTagsNamespace() {
        // A pull-request head must keep a non-branch namespace visible to the caller
        // comparing it against the expected branch, instead of losing it silently.
        assertThat(PullRequestHeads.normalizeBranch("refs/tags/v1.0")).isEqualTo("refs/tags/v1.0");
    }

    @Test
    void normalizeBranch_returnsNullForBlankInput() {
        assertThat(PullRequestHeads.normalizeBranch(null)).isNull();
        assertThat(PullRequestHeads.normalizeBranch("  ")).isNull();
    }

    @Test
    void nonBlank_returnsNullForBlankInput() {
        assertThat(PullRequestHeads.nonBlank("abc")).isEqualTo("abc");
        assertThat(PullRequestHeads.nonBlank("")).isNull();
        assertThat(PullRequestHeads.nonBlank(" ")).isNull();
        assertThat(PullRequestHeads.nonBlank(null)).isNull();
    }

    @Test
    void requiredString_failsWithTheProvidersMessage() {
        assertThat(PullRequestHeads.requiredString("abc", "unused")).isEqualTo("abc");
        assertThatThrownBy(() -> PullRequestHeads.requiredString(" ", "GitHub head ref is missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub head ref is missing");
    }
}
