package org.remus.giteabot.bitbucket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitbucketAccount(
        @Nullable String uuid,
        @JsonProperty("account_id") @Nullable String accountId,
        @Nullable String nickname,
        @JsonProperty("display_name") @Nullable String displayName
) {

    /** Treats blank values like missing ones, so an empty id never matches another account. */
    public BitbucketAccount {
        uuid = blankToNull(uuid);
        accountId = blankToNull(accountId);
        nickname = blankToNull(nickname);
        displayName = blankToNull(displayName);
    }

    /** Returns whether the raw Bitbucket user object refers to this account. */
    public boolean isSameAccount(@Nullable Map<?, ?> user) {
        if (user == null) {
            return false;
        }

        BitbucketAccount other = new BitbucketAccount(
                asString(user.get("uuid")),
                asString(user.get("account_id")),
                asString(user.get("nickname")),
                asString(user.get("display_name")));

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
        return value instanceof String s ? s : null;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
