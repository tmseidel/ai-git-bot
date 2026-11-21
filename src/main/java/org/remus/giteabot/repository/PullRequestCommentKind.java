package org.remus.giteabot.repository;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The kinds of comment a pull request carries. Providers address each kind differently, both in
 * their APIs and in the browser, see {@link RepositoryPlatform#getCommentWebUrl}.
 * <p>
 * The constant names are persisted (e.g. as the comment kind of recorded feedback), so they must
 * not be renamed.
 */
public enum PullRequestCommentKind {

    /** A comment in the pull request's conversation. */
    PR_COMMENT,

    /** A comment on a line of the pull request's diff. */
    INLINE_COMMENT,

    /** The body of a submitted pull request review (GitHub, Gitea). */
    REVIEW;

    /** Resolves a persisted name, ignoring case; {@code null} when it names no kind. */
    public static @Nullable PullRequestCommentKind fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
