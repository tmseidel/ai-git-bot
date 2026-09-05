package org.remus.giteabot.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretTemplateParserTest {

    private SecretTemplateParser parser;

    @BeforeEach
    void setUp() {
        parser = new SecretTemplateParser(new SecretSourceRegistry(
                List.of(FakeSecretSource.echoing("env"), FakeSecretSource.echoing("vault"))));
    }

    @Test
    void testParseKeepsEscapeChar() {
        SecretTemplate template = parser.parse("Hello $${env:WORLD}!");

        assertThat(template.raw()).isEqualTo("Hello ${env:WORLD}!");
    }

    @Test
    void testParseKeepsEscapeCharOnlyPattern() {
        SecretTemplate template = parser.parse("$${env:WORLD}");

        assertThat(template.raw()).isEqualTo("${env:WORLD}");
    }

    @Test
    void testParseNoMatch() {
        SecretTemplate template = parser.parse("Hello World!");

        assertThat(template.raw()).isEqualTo("Hello World!");
    }

    @Test
    void testParseMultiEscapeMatch() {
        SecretTemplate template = parser.parse("-$${env:HELLO}- -$${env:WORLD}- !");

        assertThat(template.raw()).isEqualTo("-${env:HELLO}- -${env:WORLD}- !");
    }

    @Test
    void testEscapedReferenceIsNeverResolved() {
        SecretTemplate template = parser.parse("Hello $${env:WORLD}!");

        assertThat(template.expose()).isEqualTo("Hello ${env:WORLD}!");
    }

    @Test
    void testParseSingleReference() {
        SecretTemplate template = parser.parse("${env:WORLD}");

        assertThat(template.raw()).isEqualTo("${env:WORLD}");
        assertThat(template.expose()).isEqualTo("[WORLD]");
    }

    @Test
    void testParseReferenceSurroundedByText() {
        SecretTemplate template = parser.parse("Bearer ${env:TOKEN} suffix");

        assertThat(template.raw()).isEqualTo("Bearer ${env:TOKEN} suffix");
        assertThat(template.expose()).isEqualTo("Bearer [TOKEN] suffix");
    }

    @Test
    void testParseMultipleReferences() {
        SecretTemplate template = parser.parse("${env:USER}:${vault:PASSWORD}");

        assertThat(template.raw()).isEqualTo("${env:USER}:${vault:PASSWORD}");
        assertThat(template.expose()).isEqualTo("[USER]:[PASSWORD]");
    }

    @Test
    void testParseAdjacentReferences() {
        SecretTemplate template = parser.parse("${env:A}${env:B}");

        assertThat(template.raw()).isEqualTo("${env:A}${env:B}");
        assertThat(template.expose()).isEqualTo("[A][B]");
    }

    @Test
    void testParseMixesEscapedAndUnescapedReferences() {
        SecretTemplate template = parser.parse("$${env:LITERAL} and ${env:RESOLVED}");

        assertThat(template.raw()).isEqualTo("${env:LITERAL} and ${env:RESOLVED}");
        assertThat(template.expose()).isEqualTo("${env:LITERAL} and [RESOLVED]");
    }

    @Test
    void testParseTrimsWhitespaceAroundTypeAndKey() {
        SecretTemplate template = parser.parse("${  env  :   MY_TOKEN   }");

        assertThat(template.raw()).isEqualTo("${  env  :   MY_TOKEN   }");
        assertThat(template.expose()).isEqualTo("[MY_TOKEN]");
    }

    @Test
    void testParseKeepsWhitespaceInsideKey() {
        SecretTemplate template = parser.parse("${env: my key }");

        assertThat(template.expose()).isEqualTo("[my key]");
    }

    @ParameterizedTest(name = "''{0}'' is not a secret reference")
    @ValueSource(strings = {
            "${ENV:KEY}",     // the source type must be lower case
            "${Env:KEY}",
            "${KEY}",         // no type separator
            "${env:}",        // empty key
            "${env:KEY",      // unterminated
            "$env:KEY}",      // no opening brace
            "{env:KEY}",      // no dollar sign
            "100$ for a ${coffee}"
    })
    void testParseLeavesNonMatchingInputUntouched(String value) {
        SecretTemplate template = parser.parse(value);

        assertThat(template.raw()).isEqualTo(value);
        assertThat(template.expose()).isEqualTo(value);
    }

    @Test
    void testParseNullYieldsEmptyTemplate() {
        // never null, so no caller has to null-check the parser's result
        SecretTemplate template = parser.parse(null);

        assertThat(template).isNotNull();
        assertThat(template.raw()).isEmpty();
        assertThat(template.expose()).isEmpty();
    }

    @ParameterizedTest(name = "blank input [{0}] is kept verbatim")
    @ValueSource(strings = {"", " ", "   \t "})
    void testParseBlankIsKeptVerbatim(String value) {
        SecretTemplate template = parser.parse(value);

        assertThat(template.raw()).isEqualTo(value);
        assertThat(template.expose()).isEqualTo(value);
    }

    @Test
    void testParseIsRepeatableForTheSameParserInstance() {
        assertThat(parser.parse("${env:A}").expose()).isEqualTo("[A]");
        assertThat(parser.parse("${env:B}").expose()).isEqualTo("[B]");
    }

    @Test
    void testTemplateCanBeExposedRepeatedly() {
        SecretTemplate template = parser.parse("${env:A}-${env:B}");

        assertThat(template.expose()).isEqualTo("[A]-[B]");
        assertThat(template.expose()).isEqualTo("[A]-[B]");
        assertThat(template.raw()).isEqualTo("${env:A}-${env:B}");
    }
}
