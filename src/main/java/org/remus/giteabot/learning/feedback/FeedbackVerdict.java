package org.remus.giteabot.learning.feedback;

import java.util.Optional;

/**
 * What a reviewer says about one review target. The vocabulary is closed and stored as an enum, so a
 * later analysis never has to interpret free text to learn the verdict.
 */
public enum FeedbackVerdict {

    /** The target was right and worth keeping. */
    USEFUL("useful"),

    /** The target claimed something that is not true. */
    INCORRECT("incorrect"),

    /** The target is true but does not belong in a review of this change. */
    IRRELEVANT("irrelevant"),

    /** The direction is right but the content needs work. */
    NEEDS_IMPROVEMENT("needs-improvement");

    private final String token;

    FeedbackVerdict(String token) {
        this.token = token;
    }

    /** The exact word a commenter writes; also the value stored in summaries. */
    public String token() {
        return token;
    }

    /** Accepts the canonical token and its underscore variant, case-insensitively. */
    public static Optional<FeedbackVerdict> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        for (FeedbackVerdict verdict : values()) {
            if (verdict.token.equals(normalized)) {
                return Optional.of(verdict);
            }
        }
        return Optional.empty();
    }

    public static String tokens() {
        StringBuilder sb = new StringBuilder();
        for (FeedbackVerdict verdict : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(verdict.token);
        }
        return sb.toString();
    }
}
