package org.remus.giteabot.learning.feedback;

import java.util.Locale;

/**
 * Where a feedback record came from: the exact comment that submitted it.
 *
 * <p>The normalized {@code sourceKey} is what makes webhook redelivery harmless. Every component
 * it covers is part of the identity the provider itself gives the comment, so the same comment can
 * never produce two records — and a missing component makes the submission unsupported rather than
 * silently un-deduplicated.</p>
 *
 * @param gitIntegrationId    Git integration the command arrived through
 * @param botId               bot whose agentic review is being judged
 * @param repoOwner           canonical repository owner
 * @param repoName            canonical repository name
 * @param providerRepositoryId opaque provider repository identity, when the payload exposes one
 * @param subjectKind         {@code PR} or {@code ISSUE}
 * @param subjectNumber       pull-request or issue number inside that repository
 * @param commentKind         {@code PR_COMMENT}, {@code INLINE_COMMENT}, or {@code REVIEW}
 * @param commentId           provider comment identity
 */
public record FeedbackSource(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                             String providerRepositoryId, SubjectKind subjectKind,
                             Long subjectNumber, String commentKind, String commentId) {

    public enum SubjectKind {
        PR("pr"),
        ISSUE("issue");

        private final String token;

        SubjectKind(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    public static final int MAX_COMMENT_KIND_CHARS = 32;
    public static final int MAX_COMMENT_ID_CHARS = 128;

    /** Whether every component deduplication depends on is present. */
    public boolean isIdentifiable() {
        return gitIntegrationId != null && botId != null
                && repoOwner != null && !repoOwner.isBlank()
                && repoName != null && !repoName.isBlank()
                && subjectKind != null && subjectNumber != null
                && commentKind != null && !commentKind.isBlank()
                && commentId != null && !commentId.isBlank();
    }

    /**
     * Stable, case-normalized identity of the submitting comment. Two deliveries of the same
     * comment produce the same key; two comments never do.
     */
    public String sourceKey() {
        return String.join("|",
                String.valueOf(gitIntegrationId),
                String.valueOf(botId),
                providerRepositoryId != null && !providerRepositoryId.isBlank()
                        ? "id:" + providerRepositoryId
                        : "name:" + repoOwner.trim().toLowerCase(Locale.ROOT) + "/"
                                + repoName.trim().toLowerCase(Locale.ROOT),
                subjectKind.token(),
                String.valueOf(subjectNumber),
                commentKind.toUpperCase(Locale.ROOT),
                commentId.trim());
    }
}
