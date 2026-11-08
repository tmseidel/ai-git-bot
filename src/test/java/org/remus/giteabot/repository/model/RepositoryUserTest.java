package org.remus.giteabot.repository.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUserTest {

    @Test
    void missingEmail_fallsBackToTheBotAddress() {
        assertThat(new RepositoryUser("17", "ai_bot", null, null).email()).isEqualTo("ai-agent@bot.local");
        assertThat(new RepositoryUser("17", "ai_bot", null, " ").email()).isEqualTo("ai-agent@bot.local");
    }

    @Test
    void missingDisplayName_fallsBackToTheUsername() {
        assertThat(new RepositoryUser("17", "ai_bot", null, "bot@example.com").displayName()).isEqualTo("ai_bot");
        assertThat(new RepositoryUser("17", "ai_bot", " ", "bot@example.com").displayName()).isEqualTo("ai_bot");
    }
}
