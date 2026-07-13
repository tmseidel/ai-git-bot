package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class I18nFileParserTest {

    @Test
    void parsesPropertiesPreservingKeys() {
        String content = "# comment\ngreeting = Hello\nfarewell:Bye\n\n! bang comment\nempty=";
        Map<String, String> entries = I18nFileParser.parseProperties(content);
        assertThat(entries).containsEntry("greeting", "Hello")
                .containsEntry("farewell", "Bye")
                .containsEntry("empty", "");
        assertThat(entries).hasSize(3);
    }

    @Test
    void parsesFlatJson() {
        Map<String, String> entries = I18nFileParser.parseJson("{\"greeting\":\"Hello\",\"count\":3}");
        assertThat(entries).containsEntry("greeting", "Hello").containsEntry("count", "3");
    }

    @Test
    void flattensNestedJson() {
        Map<String, String> entries = I18nFileParser.parseJson(
                "{\"menu\":{\"file\":{\"open\":\"Open\",\"close\":\"Close\"}}}");
        assertThat(entries).containsEntry("menu.file.open", "Open")
                .containsEntry("menu.file.close", "Close");
    }

    @Test
    void extractsLocaleAndFamilyFromPropertiesSuffix() {
        I18nFileParser.LocaleFile en = I18nFileParser.parse("i18n/messages_en.properties", "a=1");
        I18nFileParser.LocaleFile de = I18nFileParser.parse("i18n/messages_de_DE.properties", "a=1");
        I18nFileParser.LocaleFile def = I18nFileParser.parse("i18n/messages.properties", "a=1");

        assertThat(en.locale()).isEqualTo("en");
        assertThat(de.locale()).isEqualTo("de_DE");
        assertThat(def.locale()).isEmpty();
        // All three share the same family.
        assertThat(en.familyId()).isEqualTo(de.familyId()).isEqualTo(def.familyId());
    }

    @Test
    void extractsBareLocaleFromJsonName() {
        I18nFileParser.LocaleFile fr = I18nFileParser.parse("i18n/fr-FR.json", "{}");
        assertThat(fr.locale()).isEqualTo("fr_FR");
        I18nFileParser.LocaleFile en = I18nFileParser.parse("i18n/en.json", "{}");
        assertThat(en.familyId()).isEqualTo(fr.familyId());
    }
}
