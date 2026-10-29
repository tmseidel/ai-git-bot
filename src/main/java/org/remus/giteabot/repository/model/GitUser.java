package org.remus.giteabot.repository.model;

import org.jspecify.annotations.Nullable;

/**
 * The account a Git integration's token authenticates as, i.e. the bot's identity on the provider.
 * <p>
 * Display name and e-mail can be changed on the provider at any time, so this is only cached for
 * a limited time (see {@code GitUserService}).
 *
 * @param id          the provider's stable account id (Azure DevOps: the identity GUID, which webhook
 *                    payloads identify the bot by), or {@code null} when the provider does not expose one
 * @param username    the login webhook payloads identify the bot by (Azure DevOps: the account name)
 * @param displayName the full name, or {@code null} when the provider does not expose one
 * @param email       the e-mail address, or {@code null} when the provider does not expose one
 */
public record GitUser(@Nullable String id, String username, @Nullable String displayName,
                      @Nullable String email) {

    public GitUser {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("The Git provider returned no username for the authenticated user");
        }
        id = blankToNull(id);
        displayName = blankToNull(displayName);
        email = blankToNull(email);
    }

    /**
     * Whether the given account is this user. Compares the stable account id when both sides
     * carry one, since the username can be renamed on the provider. Otherwise falls back to the
     * username, so a payload without ids still recognises the bot.
     */
    public boolean matches(@Nullable String otherId, @Nullable String otherUsername) {
        if (id != null && otherId != null && !otherId.isBlank()) {
            return id.equalsIgnoreCase(otherId);
        }
        return username.equalsIgnoreCase(otherUsername);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
