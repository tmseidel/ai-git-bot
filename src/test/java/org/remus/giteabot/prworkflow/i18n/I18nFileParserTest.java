package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    // ── locale / family extraction (happy paths) ───────────────────────────

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

    @ParameterizedTest
    @CsvSource(textBlock = """
            i18n/en.json,            en
            i18n/fr-FR.json,         fr_FR
            i18n/messages_en.properties, en
            i18n/messages_de_DE.properties, de_DE
            i18n/messages_en_US_WIN.properties, en_US_WIN
            i18n/messages_zh_Hans_CN.properties, zh_HANS_CN
            i18n/messages_es_419.properties,    es_419
            """)
    void extractsExpectedLocale(String path, String expectedLocale) {
        String content = path.endsWith(".json") ? "{}" : "a=1";
        I18nFileParser.LocaleFile lf = I18nFileParser.parse(path, content);
        assertThat(lf.locale()).isEqualTo(expectedLocale);
    }

    // ── non-locale suffixes must NOT be mistaken for locale tokens ─────────

    @ParameterizedTest
    @CsvSource(textBlock = """
            i18n/messages_release-notes.properties
            i18n/messages_pt-BR-extra.properties
            i18n/foo_en_backup.json
            i18n/messages_extra.properties
            i18n/messages_v2.properties
            i18n/messages_new.json
            """)
    void nonLocaleSuffixesStayInBaseName(String path) {
        String content = path.endsWith(".json") ? "{}" : "a=1";
        I18nFileParser.LocaleFile lf = I18nFileParser.parse(path, content);
        assertThat(lf.locale())
                .as("non-locale suffix should not produce a locale token")
                .isEmpty();
    }

    @Test
    void nonLocaleSuffixDoesNotFragmentFamily() {
        // messages_release-notes and messages_extra should each be their own
        // single-file family — not grouped with messages.properties.
        I18nFileParser.LocaleFile base = I18nFileParser.parse("i18n/messages.properties", "a=1");
        I18nFileParser.LocaleFile relNotes = I18nFileParser.parse("i18n/messages_release-notes.properties", "a=1");
        I18nFileParser.LocaleFile extra = I18nFileParser.parse("i18n/messages_extra.properties", "a=1");

        assertThat(relNotes.familyId()).isNotEqualTo(base.familyId());
        assertThat(extra.familyId()).isNotEqualTo(base.familyId());
        assertThat(relNotes.familyId()).isNotEqualTo(extra.familyId());

        // Each has locale "" (implicit default) since none is a real locale.
        assertThat(relNotes.locale()).isEmpty();
        assertThat(extra.locale()).isEmpty();
    }
}
