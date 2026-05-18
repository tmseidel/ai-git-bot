package org.remus.giteabot.prworkflow;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-managed lookup for all {@link PrWorkflow} beans, analogous to
 * {@link org.remus.giteabot.ai.AiProviderRegistry} and
 * {@link org.remus.giteabot.repository.RepositoryProviderRegistry}.
 *
 * <p>Discovers all {@link PrWorkflow} implementations via constructor
 * injection, validates that their {@link PrWorkflow#key()} values are unique
 * (and lower-case kebab-case), and exposes them as both a key-indexed map and
 * an ordered list.</p>
 */
@Slf4j
@Service
public class PrWorkflowRegistry {

    private final Map<String, PrWorkflow> workflowsByKey = new LinkedHashMap<>();
    private final List<PrWorkflow> workflows;

    public PrWorkflowRegistry(List<PrWorkflow> workflows) {
        this.workflows = workflows;
    }

    @PostConstruct
    void index() {
        Map<String, PrWorkflow> indexed = new LinkedHashMap<>();
        Map<String, String> seenLowercaseKeys = new HashMap<>();
        for (PrWorkflow workflow : workflows) {
            String key = workflow.key();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("PrWorkflow " + workflow.getClass().getName()
                        + " returned a blank key()");
            }
            String normalised = key.toLowerCase(Locale.ROOT);
            if (!normalised.equals(key)) {
                throw new IllegalStateException("PrWorkflow key '" + key
                        + "' must be lower-case kebab-case (" + workflow.getClass().getName() + ")");
            }
            String previous = seenLowercaseKeys.put(normalised, workflow.getClass().getName());
            if (previous != null) {
                throw new IllegalStateException("Duplicate PrWorkflow key '" + key + "' registered by "
                        + previous + " and " + workflow.getClass().getName());
            }
            indexed.put(normalised, workflow);
            log.info("Registered PrWorkflow '{}' ({}) [{}]",
                    workflow.displayName(), key, workflow.getClass().getSimpleName());
        }
        workflowsByKey.clear();
        workflowsByKey.putAll(indexed);
    }

    public Optional<PrWorkflow> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowsByKey.get(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the workflow registered under {@code key} or throws if no such
     * workflow exists. Useful for the orchestrator which is always called
     * with a validated key.
     */
    public PrWorkflow require(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException(
                "No PrWorkflow registered for key '" + key + "'"));
    }

    public List<PrWorkflow> all() {
        return List.copyOf(workflowsByKey.values());
    }
}

