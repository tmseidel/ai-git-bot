package org.remus.giteabot.learning.feedback;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A typed feedback target parsed from a public reference: {@code O1234} (the review),
 * {@code O1234-F2} (one finding), or {@code O1234-A} (the assessment).
 *
 * <p>Parsing never authorizes anything. It yields a shape, and the service resolves that shape
 * against the caller's own integration/bot/repository/PR scope, so guessing a valid-looking
 * reference from another repository fails before any stored text is read.</p>
 */
public record FeedbackTarget(FeedbackTargetType type, String outputReference, int findingOrdinal) {

    private static final Pattern REFERENCE =
            Pattern.compile("^(O[1-9][0-9]{0,11})(?:-(?:F([1-9][0-9]{0,3})|A))?$",
                    Pattern.CASE_INSENSITIVE);

    public FeedbackTarget {
        if (type == FeedbackTargetType.FINDING && findingOrdinal < 1) {
            throw new IllegalArgumentException("A finding target needs an ordinal >= 1");
        }
    }

    public static FeedbackTarget output(String outputReference) {
        return new FeedbackTarget(FeedbackTargetType.OUTPUT, normalize(outputReference), 0);
    }

    public static FeedbackTarget finding(String outputReference, int ordinal) {
        return new FeedbackTarget(FeedbackTargetType.FINDING, normalize(outputReference), ordinal);
    }

    public static FeedbackTarget assessment(String outputReference) {
        return new FeedbackTarget(FeedbackTargetType.ASSESSMENT, normalize(outputReference), 0);
    }

    /** The reference exactly as a commenter would write it. */
    public String reference() {
        return switch (type) {
            case OUTPUT -> outputReference;
            case FINDING -> outputReference + "-F" + findingOrdinal;
            case ASSESSMENT -> outputReference + "-A";
        };
    }

    public static Optional<FeedbackTarget> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String output = normalize(matcher.group(1));
        String ordinal = matcher.group(2);
        if (ordinal != null) {
            return Optional.of(finding(output, Integer.parseInt(ordinal)));
        }
        if (raw.trim().toUpperCase(java.util.Locale.ROOT).endsWith("-A")) {
            return Optional.of(assessment(output));
        }
        return Optional.of(output(output));
    }

    private static String normalize(String outputReference) {
        return outputReference == null ? null
                : outputReference.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
