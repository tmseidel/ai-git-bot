package org.remus.giteabot.repository.model;

import java.util.Objects;

/**
 * Git author identity used for commits the bot pushes from a workspace.
 * Both parts are required: {@code GitIntegrationService.save} rejects blank values
 * (and values longer than the 255-character column) when the Git integration is saved.
 */
public record GitAuthor(String name, String email) {

    public static final String DEFAULT_NAME = "AI Agent";
    public static final String DEFAULT_EMAIL = "ai-agent@bot.local";
    public static final GitAuthor DEFAULT = new GitAuthor(DEFAULT_NAME, DEFAULT_EMAIL);

    public GitAuthor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
    }
}
