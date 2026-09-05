package org.remus.giteabot.secret;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class SecretSourceRegistry {

    private final List<SecretSource> secretSources;

    public SecretSourceRegistry(List<SecretSource> secretSources) {
        this.secretSources = secretSources;
    }

    Optional<SecretSource> retrieve(String type) {
        for (SecretSource secretSource : secretSources) {
            if (Objects.equals(secretSource.type(), type)) {
                return Optional.of(secretSource);
            }
        }
        return Optional.empty();
    }
}
