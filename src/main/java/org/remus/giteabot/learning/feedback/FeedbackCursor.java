package org.remus.giteabot.learning.feedback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Opaque continuation token for {@code feedback list}.
 *
 * <p>The token carries the last row id of a page plus a fingerprint of the listing it belongs to
 * (integration, bot, repository and pull request or repository-wide). A cursor copied into another
 * pull request, repository or bot — or from a pull-request listing into a repository listing — is
 * refused instead of silently continuing a different listing. Queries stay scoped either way; the
 * fingerprint only makes the mismatch explicit.</p>
 */
public final class FeedbackCursor {

    private static final String VERSION = "v1";
    private static final int FINGERPRINT_CHARS = 16;

    private FeedbackCursor() {
    }

    public static String encode(FeedbackScope scope, boolean repositoryWide, long lastId) {
        String raw = lastId + "." + fingerprint(scope, repositoryWide);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the id to continue below; throws when the token is not a cursor of this listing */
    public static long decode(String token, FeedbackScope scope, boolean repositoryWide) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        int dot = raw.indexOf('.');
        if (dot <= 0 || !raw.substring(dot + 1).equals(fingerprint(scope, repositoryWide))) {
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

    private static FeedbackRejectedException invalid() {
        return new FeedbackRejectedException("invalid-cursor",
                "That page cursor does not belong to this listing. Start again with `feedback list`.");
    }

    private static String fingerprint(FeedbackScope scope, boolean repositoryWide) {
        String identity = String.join("|", VERSION,
                String.valueOf(scope.gitIntegrationId()),
                String.valueOf(scope.botId()),
                lower(scope.repoOwner()),
                lower(scope.repoName()),
                repositoryWide ? "repository" : "pr:" + scope.prNumber());
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
