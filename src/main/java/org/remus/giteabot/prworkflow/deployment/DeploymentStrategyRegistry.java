package org.remus.giteabot.prworkflow.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-managed lookup for all {@link DeploymentStrategy} beans, analogous
 * to {@link org.remus.giteabot.prworkflow.PrWorkflowRegistry}. Indexes on
 * construction so package-external tests can use it without triggering the
 * Spring lifecycle.
 */
@Slf4j
@Service
public class DeploymentStrategyRegistry {

    private final Map<DeploymentStrategyType, DeploymentStrategy> byType =
            new EnumMap<>(DeploymentStrategyType.class);

    public DeploymentStrategyRegistry(List<DeploymentStrategy> strategies) {
        for (DeploymentStrategy strategy : strategies) {
            DeploymentStrategyType key = strategy.typeKey();
            if (key == null) {
                throw new IllegalStateException("DeploymentStrategy "
                        + strategy.getClass().getName() + " returned null typeKey()");
            }
            DeploymentStrategy previous = byType.put(key, strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate deployment strategy registered for "
                        + key + ": " + previous.getClass().getName() + " and "
                        + strategy.getClass().getName());
            }
            log.info("Registered DeploymentStrategy [{}] -> {}", key, strategy.getClass().getSimpleName());
        }
    }

    public Optional<DeploymentStrategy> find(DeploymentStrategyType type) {
        return Optional.ofNullable(byType.get(type));
    }

    public DeploymentStrategy require(DeploymentStrategyType type) {
        return find(type).orElseThrow(() -> new IllegalArgumentException(
                "No DeploymentStrategy registered for type " + type));
    }
}
