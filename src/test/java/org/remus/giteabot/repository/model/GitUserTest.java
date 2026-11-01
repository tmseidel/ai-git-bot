package org.remus.giteabot.repository.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitUserTest {

    private static final GitUser BOT = new GitUser("17", "ai_bot", null, null);

    @Test
    void matches_comparesTheIdWhenBothSidesCarryOne() {
        assertThat(BOT.matches("17", "renamed_bot")).isTrue();
        assertThat(BOT.matches("99", "ai_bot")).isFalse();
    }

    @Test
    void matches_fallsBackToTheUsernameWithoutAnId() {
        assertThat(BOT.matches(null, "AI_Bot")).isTrue();
        assertThat(BOT.matches(" ", "ai_bot")).isTrue();
        assertThat(BOT.matches(null, "alice")).isFalse();
        assertThat(BOT.matches(null, null)).isFalse();
        assertThat(new GitUser(null, "ai_bot", null, null).matches("17", "ai_bot")).isTrue();
    }
}
