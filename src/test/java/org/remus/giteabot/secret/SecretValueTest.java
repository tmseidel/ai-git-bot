package org.remus.giteabot.secret;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecretValue} is a record, so its accessors, {@code equals} and {@code hashCode} are
 * generated and need no coverage. What is worth pinning are the two properties specific to a
 * type that carries a secret.
 */
class SecretValueTest {

    @Test
    void testToStringDoesNotLeakTheValue() {
        // the only hand-written member: a generated toString would render the secret, and this
        // one ends up in logs and exception messages
        SecretValue secretValue = new SecretValue("s3cret", "env", "TOKEN");

        assertThat(secretValue.toString())
                .doesNotContain("s3cret")
                .contains("env")
                .contains("TOKEN");
    }

    @Test
    void testEqualsDistinguishesARotatedSecret() {
        // guards against reintroducing a hand-written equals that ignores the value: the same
        // reference resolved before and after a rotation must not look interchangeable to a
        // cache or a Set
        SecretValue before = new SecretValue("old", "env", "TOKEN");
        SecretValue after = new SecretValue("new", "env", "TOKEN");

        assertThat(before).isNotEqualTo(after);
    }
}
