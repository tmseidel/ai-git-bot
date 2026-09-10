package org.remus.giteabot.secret;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolution behaviour of {@link SecretTemplate}. The templates are assembled
 * directly from segments so every branch can be reached independently of the parser.
 */
class SecretTemplateTest {

    private static final Segment.SecretReference TOKEN =
            new Segment.SecretReference("env", "TOKEN", "${env:TOKEN}");

    @Test
    void testExposeResolvesReference() {
        SecretTemplate template = template(
                List.of(FakeSecretSource.of("env", Map.of("TOKEN", "s3cret"))),
                literal("Bearer "), TOKEN);

        assertThat(template.expose()).isEqualTo("Bearer s3cret");
    }

    @Test
    void testExposeResolvesEachReferenceFromItsOwnSource() {
        SecretTemplate template = template(
                List.of(FakeSecretSource.of("env", Map.of("USER", "alice")),
                        FakeSecretSource.of("vault", Map.of("PASSWORD", "pw"))),
                new Segment.SecretReference("env", "USER", "${env:USER}"),
                literal(":"),
                new Segment.SecretReference("vault", "PASSWORD", "${vault:PASSWORD}"));

        assertThat(template.expose()).isEqualTo("alice:pw");
    }

    @Test
    void testExposeFallsBackToRawWhenSourceTypeIsUnknown() {
        SecretTemplate template = template(
                List.of(FakeSecretSource.of("vault", Map.of("TOKEN", "s3cret"))),
                literal("Bearer "), TOKEN);

        assertThat(template.expose()).isEqualTo("Bearer ${env:TOKEN}");
    }

    @Test
    void testExposeFallsBackToRawWhenNoSourceIsRegistered() {
        SecretTemplate template = template(List.of(), literal("Bearer "), TOKEN);

        assertThat(template.expose()).isEqualTo("Bearer ${env:TOKEN}");
    }

    @Test
    void testExposeFallsBackToRawWhenKeyIsUnresolved() {
        SecretTemplate template = template(List.of(FakeSecretSource.empty("env")), literal("Bearer "), TOKEN);

        assertThat(template.expose()).isEqualTo("Bearer ${env:TOKEN}");
    }

    @Test
    void testExposePropagatesResolveFailures() {
        SecretTemplate template = template(List.of(FakeSecretSource.failing("env")), TOKEN);

        assertThatThrownBy(template::expose)
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining("TOKEN");
    }

    @Test
    void testExposeQueriesTheSourceOnEveryCall() {
        FakeSecretSource source = FakeSecretSource.of("env", Map.of("TOKEN", "s3cret"));
        SecretTemplate template = template(List.of(source), TOKEN);

        template.expose();
        template.expose();

        assertThat(source.resolveCalls("TOKEN")).isEqualTo(2);
    }

    @Test
    void testRawNeverResolvesAnything() {
        FakeSecretSource source = FakeSecretSource.of("env", Map.of("TOKEN", "s3cret"));
        SecretTemplate template = template(List.of(source), literal("Bearer "), TOKEN, literal("!"));

        assertThat(template.raw()).isEqualTo("Bearer ${env:TOKEN}!");
        assertThat(source.resolveCalls("TOKEN")).isZero();
    }

    @Test
    void testExposeStripsWhitespaceAroundAResolvedValue() {
        // a secret read from a file commonly ends with a newline, which a consumer like an
        // HTTP header would refuse - stripping the resolved value, not the assembled result,
        // is what keeps that working for a reference that is not at the end of the template
        SecretTemplate template = template(
                List.of(FakeSecretSource.of("env", Map.of("TOKEN", "  s3cret\n"))),
                literal("Bearer "), TOKEN, literal(" suffix"));

        assertThat(template.expose()).isEqualTo("Bearer s3cret suffix");
    }

    @Test
    void testExposeKeepsWhitespaceOfALiteralSegment() {
        // only the resolved value is stripped; literal text is transmitted as configured
        SecretTemplate template = template(List.of(), literal("  literal  "));

        assertThat(template.expose()).isEqualTo("  literal  ");
    }

    private static SecretTemplate template(List<SecretSource> sources, Segment... segments) {
        return new SecretTemplate(registry(sources), Arrays.asList(segments));
    }

    private static Segment.Literal literal(String text) {
        return new Segment.Literal(text);
    }

    private static SecretSourceRegistry registry(List<SecretSource> sources) {
        return new SecretSourceRegistry(sources);
    }
}
