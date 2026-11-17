package org.remus.giteabot.learning.feedback;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public {@code FB<n>} identity of one feedback record. */
public final class FeedbackReferences {

    private static final Pattern REFERENCE =
            Pattern.compile("^(FB[1-9][0-9]{0,11})$", Pattern.CASE_INSENSITIVE);

    private FeedbackReferences() {
    }

    /** The canonical form, or {@code null} when {@code raw} is not a feedback reference. */
    public static String parse(String raw) {
        Matcher matcher = REFERENCE.matcher(raw == null ? "" : raw.trim());
        return matcher.matches()
                ? matcher.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    public static Optional<String> tryParse(String raw) {
        return Optional.ofNullable(parse(raw));
    }
}
