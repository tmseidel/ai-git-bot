package org.remus.giteabot.bitbucket;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Identity of a Bitbucket Cloud account, as returned by {@code GET /user} or embedded
 * in webhook payloads ({@code actor}, {@code comment.user}, {@code reviewers}).
 * <p>
 * Webhook payloads never expose e-mail addresses, so the bot's own account is resolved
 * once from the configured e-mail/API-token pair and compared by its stable
 * {@code account_id} / {@code uuid} afterwards.
 */
public record BitbucketAccount(
        @Nullable String uuid,
        @Nullable String accountId,
        @Nullable String nickname,
        @Nullable String displayName
) {

    /** Builds an account from a raw Bitbucket user object; returns {@code null} for {@code null} input. */
    public static @Nullable BitbucketAccount fromMap(@Nullable Map<?, ?> user) {
        if (user == null) {
            return null;
        }
        return new BitbucketAccount(
                asString(user.get("uuid")),
                asString(user.get("account_id")),
                asString(user.get("nickname")),
                asString(user.get("display_name")));
    }

    /** Returns whether the raw Bitbucket user object refers to this account. */
    public boolean isSameAccount(@Nullable Map<?, ?> user) {
        BitbucketAccount other = fromMap(user);
        if (other == null) {
            return false;
        }
        if (accountId != null && accountId.equals(other.accountId)) {
            return true;
        }
        return uuid != null && uuid.equals(other.uuid);
    }

    /**
     * Returns the mention token Bitbucket stores in a comment's raw markdown when this
     * account is @-mentioned (e.g. {@code @{557058:abc...}}), or an empty string when
     * the account id is unknown.
     */
    public String mention() {
        return accountId == null || accountId.isBlank() ? "" : "@{" + accountId + "}";
    }

    /**
     * Returns a human-readable replacement for {@link #mention()}, e.g. {@code @AI_Bot} for the
     * display name "AI Bot". Whitespace is replaced by {@code _} so that slash-command parsing
     * ({@code @\S+ command}) still works; falls back to {@link #mention()} without a name.
     */
    public String readableMention() {
        String name = name();
        return name == null ? mention() : "@" + name.trim().replaceAll("\\s+", "_");
    }

    /**
     * Returns the name users see and search for in Bitbucket's {@code @} autocomplete:
     * the display name, else the nickname, or {@code null} when neither is known.
     */
    public @Nullable String name() {
        return displayName != null ? displayName : nickname;
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
