package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class I18nFileParserTest {

    // ── .properties parsing (Java Properties-compatible semantics) ──────────

    @Test
    void parsesPropertiesPreservingKeys() {
        // No whitespace around separators — Properties uses the first
        // unescaped =, :, or whitespace as the separator.
        String content = "# comment\ngreeting=Hello\nfarewell:Bye\n\n! bang comment\nempty=";
        Map<String, String> entries = I18nFileParser.parseProperties(content);
        assertThat(entries).containsEntry("greeting", "Hello")
                .containsEntry("farewell", "Bye")
                .containsEntry("empty", "");
        assertThat(entries).hasSize(3);
    }

    @Test
    void whitespaceSeparator() {
        // Java Properties supports whitespace as key/value separator.
        Map<String, String> entries = I18nFileParser.parseProperties("greeting Hello\nkey:value");
        assertThat(entries).containsEntry("greeting", "Hello")
                .containsEntry("key", "value");
    }

    @Test
    void whitespaceSeparatorSkipsTrailingSpace() {
        Map<String, String> entries = I18nFileParser.parseProperties("greeting   Hello World");
        assertThat(entries).containsEntry("greeting", "Hello World");
    }

    @Test
    void lineContinuation() {
        String content = "greeting Hello,\\\n World!\nfruit Apple";
        Map<String, String> entries = I18nFileParser.parseProperties(content);
        assertThat(entries).containsEntry("greeting", "Hello, World!")
                .containsEntry("fruit", "Apple");
    }

    @Test
    void lineContinuationMultipleLines() {
        String content = "key This is a \\\n very \\\n long value";
        Map<String, String> entries = I18nFileParser.parseProperties(content);
        assertThat(entries).containsEntry("key", "This is a  very  long value");
    }

    @Test
    void escapedEqualsInKey() {
        // key\\=with\\=equals=value — no space before =, so = is the separator.
        Map<String, String> entries = I18nFileParser.parseProperties("key\\=with\\=equals=value");
        assertThat(entries).containsEntry("key=with=equals", "value");
    }

    @Test
    void escapedColonInKey() {
        Map<String, String> entries = I18nFileParser.parseProperties("key\\:with\\:colons=value");
        assertThat(entries).containsEntry("key:with:colons", "value");
    }

    @Test
    void escapedSpaceInKey() {
        Map<String, String> entries = I18nFileParser.parseProperties("key\\ with\\ spaces=value");
        assertThat(entries).containsEntry("key with spaces", "value");
    }

    @Test
    void noSeparatorMeansKeyWithEmptyValue() {
        Map<String, String> entries = I18nFileParser.parseProperties("standalone_key");
        assertThat(entries).containsEntry("standalone_key", "");
    }

    @Test
    void controlCharEscapesInKey() {
        Map<String, String> entries = I18nFileParser.parseProperties(
                "tab\\tkey=x\nnewline\\nkey=y\ncarriage\\rreturn=z");
        assertThat(entries).containsEntry("tab\tkey", "x")
                .containsEntry("newline\nkey", "y")
                .containsEntry("carriage\rreturn", "z");
    }

    @Test
    void unicodeEscapeInKey() {
        Map<String, String> entries = I18nFileParser.parseProperties("gr\\u00FC\\u00DFe=hello");
        assertThat(entries).containsEntry("grüße", "hello");
    }

    @Test
    void preservesKeyOrder() {
        String content = "z=last\na=first\nm=middle";
        Map<String, String> entries = I18nFileParser.parseProperties(content);
        assertThat(entries.keySet()).containsExactly("z", "a", "m");
    }

    @Test
    void whitespaceBeforeEqualsMeansSpaceIsSeparator() {
        // Properties semantics: first unescaped whitespace is the separator.
        // "greeting =Hello" → key=greeting, value==Hello
        Map<String, String> entries = I18nFileParser.parseProperties("greeting =Hello");
        assertThat(entries).containsEntry("greeting", "=Hello");
    }

    // ── loadConvert ─────────────────────────────────────────────────────────

    @Test
    void loadConvertPlainKey() {
        assertThat(I18nFileParser.loadConvert("key")).isEqualTo("key");
    }

    @Test
    void loadConvertEscapedEquals() {
        assertThat(I18nFileParser.loadConvert("key\\=escaped")).isEqualTo("key=escaped");
    }

    @Test
    void loadConvertEscapedColon() {
        assertThat(I18nFileParser.loadConvert("key\\:colon")).isEqualTo("key:colon");
    }

    @Test
    void loadConvertEscapedSpace() {
        assertThat(I18nFileParser.loadConvert("key\\ space")).isEqualTo("key space");
    }

    @Test
    void loadConvertTab() {
        assertThat(I18nFileParser.loadConvert("tab\\tkey")).isEqualTo("tab\tkey");
    }

    @Test
    void loadConvertNewline() {
        assertThat(I18nFileParser.loadConvert("nl\\nkey")).isEqualTo("nl\nkey");
    }

    @Test
    void loadConvertCarriageReturn() {
        assertThat(I18nFileParser.loadConvert("cr\\rkey")).isEqualTo("cr\rkey");
    }

    @Test
    void loadConvertFormFeed() {
        assertThat(I18nFileParser.loadConvert("ff\\fkey")).isEqualTo("ff\fkey");
    }

    @Test
    void loadConvertBackslash() {
        assertThat(I18nFileParser.loadConvert("bs\\\\key")).isEqualTo("bs\\key");
    }

    // ── JSON parsing ────────────────────────────────────────────────────────

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
    void isNestedJsonDetectsNestedObject() {
        assertThat(I18nFileParser.isNestedJson("{\"a\":{\"b\":1}}")).isTrue();
    }

    @Test
    void isNestedJsonDetectsNestedArray() {
        assertThat(I18nFileParser.isNestedJson("{\"a\":[1,2,3]}")).isTrue();
    }

    @Test
    void isNestedJsonDetectsArrayRoot() {
        assertThat(I18nFileParser.isNestedJson("[{\"a\":1}]")).isTrue();
    }

    @Test
    void isNestedJsonReturnsFalseForFlatObject() {
        assertThat(I18nFileParser.isNestedJson("{\"a\":\"A\",\"b\":3,\"c\":true}")).isFalse();
    }

    @Test
    void isNestedJsonReturnsFalseForEmpty() {
        assertThat(I18nFileParser.isNestedJson("")).isFalse();
        assertThat(I18nFileParser.isNestedJson(null)).isFalse();
    }

    // ── locale / family extraction ──────────────────────────────────────────

    @Test
    void extractsLocaleAndFamilyFromPropertiesSuffix() {
        I18nFileParser.LocaleFile en = I18nFileParser.parse("i18n/messages_en.properties", "a=1");
        I18nFileParser.LocaleFile de = I18nFileParser.parse("i18n/messages_de_DE.properties", "a=1");
        I18nFileParser.LocaleFile def = I18nFileParser.parse("i18n/messages.properties", "a=1");

        assertThat(en.locale()).isEqualTo("en");
        assertThat(de.locale()).isEqualTo("de_DE");
        assertThat(def.locale()).isEmpty();
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
        I18nFileParser.LocaleFile base = I18nFileParser.parse("i18n/messages.properties", "a=1");
        I18nFileParser.LocaleFile relNotes = I18nFileParser.parse("i18n/messages_release-notes.properties", "a=1");
        I18nFileParser.LocaleFile extra = I18nFileParser.parse("i18n/messages_extra.properties", "a=1");

        assertThat(relNotes.familyId()).isNotEqualTo(base.familyId());
        assertThat(extra.familyId()).isNotEqualTo(base.familyId());
        assertThat(relNotes.familyId()).isNotEqualTo(extra.familyId());
        assertThat(relNotes.locale()).isEmpty();
        assertThat(extra.locale()).isEmpty();
    }
}
