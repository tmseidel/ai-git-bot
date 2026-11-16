package org.remus.giteabot.event;

/**
 * Provider-neutral classification of an incoming webhook, produced by a
 * {@link WebhookTranslator} and acted on by {@link org.remus.giteabot.webhook.WebhookEventDispatcher}.
 * <p>
 * Each kind describes <em>what happened</em>, already judged against the bot where
 * that judgement needs provider-specific identity matching. Whether the bot then
 * acts on it (run-on-create, run-on-update, mention required, ...) is decided by the
 * dispatcher alone.
 */
public enum WebhookEventKind {

    /** A pull request was opened. */
    PR_OPENED,

    /** A closed pull request was reopened. */
    PR_REOPENED,

    /**
     * New commits were pushed to the pull request's source branch. Translators must
     * emit this only for actual pushes, not for title edits, votes or label changes.
     */
    PR_SYNCHRONIZED,

    /** The bot was newly requested as a reviewer of an existing pull request. */
    PR_REVIEWER_ADDED,

    /** The bot was newly assigned to an existing pull request. */
    PR_ASSIGNED,

    /** The pull request was closed, merged, declined or abandoned. */
    PR_CLOSED,

    /** A review (approval, change request or comment review) was submitted. */
    PR_REVIEW_SUBMITTED,

    /** A comment was created on the pull request's conversation. */
    PR_COMMENT,

    /** A comment was created on a file/line of the pull request's diff. */
    PR_INLINE_COMMENT,

    /** An issue was opened. */
    ISSUE_OPENED,

    /** A closed issue was reopened. */
    ISSUE_REOPENED,

    /** The bot was newly assigned to an issue. */
    ISSUE_ASSIGNED,

    /** A comment was created on an issue (not a pull request). */
    ISSUE_COMMENT,

    /** Nothing the bot reacts to; see {@link WebhookEvent#ignoreReason()}. */
    IGNORED
}
