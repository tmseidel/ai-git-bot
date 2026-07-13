package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class I18nPathGuardTest {

    private final List<String> patterns = List.of("messages_*.properties", "i18n/*.json", "**/i18n/*.json");

    @Test
    void acceptsPropertiesMatchingPattern() {
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "messages_de.properties")).isTrue();
    }

    @Test
    void acceptsJsonMatchingPattern() {
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "i18n/fr.json")).isTrue();
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "src/main/resources/i18n/ko.json")).isTrue();
    }

    @Test
    void rejectsNonI18nExtension() {
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "i18n/notes.txt")).isFalse();
    }

    @Test
    void rejectsOutOfScopePath() {
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "src/App.java")).isFalse();
        assertThat(I18nPathGuard.isAllowedI18nPath(patterns, "config/de.json")).isFalse();
    }

    @Test
    void rejectsWhenNoPatterns() {
        assertThat(I18nPathGuard.isAllowedI18nPath(List.of(), "messages_de.properties")).isFalse();
    }

    @Test
    void parsePatternsSplitsCommaAndNewline() {
        assertThat(I18nPathGuard.parsePatterns("messages_*.properties, i18n/*.json\ndocs/*.json"))
                .containsExactly("messages_*.properties", "i18n/*.json", "docs/*.json");
    }
}
