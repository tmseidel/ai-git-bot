package org.remus.giteabot.repository.model;

/**
 * The account a Git integration's token authenticates as, i.e. the bot's identity on the provider.
 * <p>
 * Display name and e-mail can be changed on the provider at any time, so this is only cached for
 * a limited time (see {@link org.remus.giteabot.repository.RepositoryPlatform#getUser(String)}).
 *
 * @param id          the provider's stable account id (Azure DevOps: the identity GUID, which webhook
 *                    payloads identify the bot by)
 * @param username    the login webhook payloads identify the bot by (Azure DevOps: the account name)
 * @param displayName the full name, or the {@code username} when the provider does not expose one
 * @param email       the e-mail address the provider links commits to the account by, or
 *                    {@link #FALLBACK_EMAIL} when the token may not read it
 */
public record RepositoryUser(String id, String username, String displayName,
                             String email) {

    /** Commit address of a bot whose e-mail is unknown; the provider cannot link it to a profile. */
    public static final String FALLBACK_EMAIL = "ai-agent@bot.local";

    public RepositoryUser {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("The Git provider returned no id for the authenticated user");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("The Git provider returned no username for the authenticated user");
        }
        email = email != null && !email.isBlank() ? email : FALLBACK_EMAIL;
        displayName = displayName != null && !displayName.isBlank() ? displayName : username;
    }
}
