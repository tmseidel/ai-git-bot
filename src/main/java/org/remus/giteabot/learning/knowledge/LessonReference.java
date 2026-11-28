package org.remus.giteabot.learning.knowledge;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public identity of a lesson ({@code L17}) or one of its revisions ({@code L17@2}).
 *
 * <p>Parsing never authorizes anything: the service resolves the reference inside the caller's
 * integration/bot/repository scope before reading stored text.</p>
 *
 * @param lesson   canonical lesson reference, e.g. {@code L17}
 * @param revision revision number, or {@code null} when the reference names the whole lesson
 */
public record LessonReference(String lesson, Integer revision) {

    private static final Pattern REFERENCE =
            Pattern.compile("^(L[1-9][0-9]{0,11})(?:@([1-9][0-9]{0,4}))?$", Pattern.CASE_INSENSITIVE);

    public static Optional<LessonReference> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new LessonReference(matcher.group(1).toUpperCase(Locale.ROOT),
                matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2))));
    }

    public static String revision(String lesson, int revision) {
        return lesson + "@" + revision;
    }

    /** As a commenter writes it. */
    @Override
    public String toString() {
        return revision == null ? lesson : revision(lesson, revision);
    }
}
