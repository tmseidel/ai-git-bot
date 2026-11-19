package org.remus.giteabot.learning.feedback;

import org.remus.giteabot.repository.RepositoryType;

/**
 * Browser links from feedback overviews back to the pull request and the comment that submitted
 * the feedback. Anchors are only produced where the provider's comment anchor is known; a wrong
 * link is worse than none, so everything else renders as plain text.
 */
public final class FeedbackLinks {

    public static final FeedbackLinks NONE = new FeedbackLinks(null, null);

    private final RepositoryType type;
    private final String webBase;

    private FeedbackLinks(RepositoryType type, String webBase) {
        this.type = type;
        this.webBase = webBase;
    }

    /**
     * @param cloneBaseUrl the integration's credential-free web base, e.g. {@code https://github.com}
     */
    public static FeedbackLinks of(RepositoryType type, String cloneBaseUrl, String owner,
                                   String repo) {
        if (type == null || type == RepositoryType.AZURE_DEVOPS || isBlank(cloneBaseUrl)
                || isBlank(owner) || isBlank(repo)) {
            return NONE;
        }
        String base = cloneBaseUrl.endsWith("/")
                ? cloneBaseUrl.substring(0, cloneBaseUrl.length() - 1) : cloneBaseUrl;
        return new FeedbackLinks(type, base + "/" + owner + "/" + repo);
    }

    /** {@code null} when no link can be built. */
    public String pullRequest(Long number) {
        if (type == null || number == null) {
            return null;
        }
        return switch (type) {
            case GITEA -> webBase + "/pulls/" + number;
            case GITHUB -> webBase + "/pull/" + number;
            case GITLAB -> webBase + "/-/merge_requests/" + number;
            case BITBUCKET -> webBase + "/pull-requests/" + number;
            case AZURE_DEVOPS -> null;
        };
    }

    /** {@code null} when the provider's anchor for this comment kind is not known. */
    public String comment(Long prNumber, String commentKind, String commentId) {
        String pullRequest = pullRequest(prNumber);
        if (pullRequest == null || commentKind == null || commentId == null
                || !commentId.chars().allMatch(Character::isDigit) || commentId.isEmpty()) {
            return null;
        }
        String anchor = switch (type) {
            case GITHUB -> switch (commentKind) {
                case FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT -> "#issuecomment-";
                case FeedbackCommandHandler.COMMENT_KIND_INLINE -> "#discussion_r";
                case FeedbackCommandHandler.COMMENT_KIND_REVIEW -> "#pullrequestreview-";
                default -> null;
            };
            case GITEA -> switch (commentKind) {
                case FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT,
                     FeedbackCommandHandler.COMMENT_KIND_INLINE -> "#issuecomment-";
                default -> null;
            };
            case GITLAB -> switch (commentKind) {
                case FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT,
                     FeedbackCommandHandler.COMMENT_KIND_INLINE -> "#note_";
                default -> null;
            };
            case BITBUCKET -> switch (commentKind) {
                case FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT,
                     FeedbackCommandHandler.COMMENT_KIND_INLINE -> "#comment-";
                default -> null;
            };
            case AZURE_DEVOPS -> null;
        };
        return anchor == null ? null : pullRequest + anchor + commentId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
