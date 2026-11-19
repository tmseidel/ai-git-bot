package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.model.RepositoryUser;

/**
 * Identity of a Bitbucket Cloud account, as returned by {@code GET /user}.
 * <p>
 * Supplies the {@code @{account_id}} markup Bitbucket stores mentions as, and a readable
 * replacement for it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitbucketAccount(
        @JsonProperty("account_id") String accountId,
        // called public name in bitbucket Account Settings
        String nickname,
        // called full name in bitbucket Account Settings
        @JsonProperty("display_name") String displayName
) {

    /** Treats blank values like missing ones, so an empty id never yields a mention. */
    public BitbucketAccount {
        accountId = blankToNull(accountId);
        nickname = blankToNull(nickname);
        displayName = blankToNull(displayName);
    }

    /**
     * The Bitbucket account behind {@code user}, the account a Bitbucket integration
     * authenticates as: its {@linkplain RepositoryUser#id() id} is the {@code account_id} and
     * its {@linkplain RepositoryUser#displayName() display name} the account's {@link #name()}.
     */
    public static BitbucketAccount of(RepositoryUser user) {
        return new BitbucketAccount(user.id(), null, user.displayName());
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

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
