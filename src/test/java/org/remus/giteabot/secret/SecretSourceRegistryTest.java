package org.remus.giteabot.secret;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SecretSourceRegistryTest {

    @Test
    void testRetrieveReturnsTheSourceWithMatchingType() {
        FakeSecretSource env = FakeSecretSource.echoing("env");
        FakeSecretSource vault = FakeSecretSource.echoing("vault");
        SecretSourceRegistry registry = new SecretSourceRegistry(List.of(env, vault));

        assertThat(registry.retrieve("env")).containsSame(env);
        assertThat(registry.retrieve("vault")).containsSame(vault);
    }

    @Test
    void testRetrieveIsEmptyForUnknownType() {
        SecretSourceRegistry registry = new SecretSourceRegistry(List.of(FakeSecretSource.echoing("env")));

        assertThat(registry.retrieve("vault")).isEmpty();
    }

    @Test
    void testRetrieveIsCaseSensitive() {
        SecretSourceRegistry registry = new SecretSourceRegistry(List.of(FakeSecretSource.echoing("env")));

        assertThat(registry.retrieve("ENV")).isEmpty();
    }

    @Test
    void testRetrieveReturnsTheFirstSourceOnDuplicateType() {
        FakeSecretSource first = FakeSecretSource.of("env", Map.of("KEY", "first"));
        FakeSecretSource second = FakeSecretSource.of("env", Map.of("KEY", "second"));
        SecretSourceRegistry registry = new SecretSourceRegistry(List.of(first, second));

        Optional<SecretSource> retrieved = registry.retrieve("env");

        assertThat(retrieved).containsSame(first);
    }
}
