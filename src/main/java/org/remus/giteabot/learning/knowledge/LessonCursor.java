package org.remus.giteabot.learning.knowledge;

import org.remus.giteabot.learning.feedback.FeedbackScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Opaque continuation token for {@code learning list}, built like the feedback cursor: the last row
 * id of a page plus a fingerprint of the listing (integration, bot, repository and state filter). A
 * cursor copied into another repository or bot, or from {@code list active} into {@code list}, is
 * refused instead of silently continuing a different listing. Queries stay scoped either way.
 */
public final class LessonCursor {

    private static final String VERSION = "v1";
    private static final int FINGERPRINT_CHARS = 16;

    private LessonCursor() {
    }

    public static String encode(FeedbackScope scope, LessonState filter, long lastId) {
        String raw = lastId + "." + fingerprint(scope, filter);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the id to continue below; throws when the token is not a cursor of this listing */
    public static long decode(String token, FeedbackScope scope, LessonState filter) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        int dot = raw.indexOf('.');
        if (dot <= 0 || !raw.substring(dot + 1).equals(fingerprint(scope, filter))) {
            throw invalid();
        }
        try {
            long id = Long.parseLong(raw.substring(0, dot));
            if (id <= 0) {
                throw invalid();
            }
            return id;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static LessonRejectedException invalid() {
        return new LessonRejectedException("invalid-cursor",
                "That page cursor does not belong to this listing. Start again with `learning list`.");
    }

    private static String fingerprint(FeedbackScope scope, LessonState filter) {
        String identity = String.join("|", VERSION, "lessons",
                String.valueOf(scope.gitIntegrationId()),
                String.valueOf(scope.botId()),
                lower(scope.repoOwner()),
                lower(scope.repoName()),
                filter == null ? "all" : filter.name());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
