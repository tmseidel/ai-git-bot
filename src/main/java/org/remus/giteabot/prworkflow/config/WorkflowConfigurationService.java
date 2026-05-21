package org.remus.giteabot.prworkflow.config;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD + clone for reusable {@link WorkflowConfiguration} rows. Mirrors
 * {@link org.remus.giteabot.systemsettings.BotToolConfigurationService} so the
 * admin UX (list / new / edit / delete / clone) stays uniform across both
 * "kinds" of bot configurations.
 *
 * <p>The default configuration is protected: it cannot be renamed, deleted,
 * nor have its default flag cleared. The default row itself is seeded by
 * Flyway migration {@code V15__workflow_configurations_default.sql}; any
 * additional REVIEW workflows shipped in later releases must add their own
 * follow-up migration — the application does not auto-extend the Default at
 * runtime.</p>
 */
@Service
@Transactional
public class WorkflowConfigurationService {

    private final WorkflowConfigurationRepository configurationRepository;
    private final WorkflowSelectionRepository selectionRepository;
    private final BotRepository botRepository;

    public WorkflowConfigurationService(WorkflowConfigurationRepository configurationRepository,
                                        WorkflowSelectionRepository selectionRepository,
                                        BotRepository botRepository) {
        this.configurationRepository = configurationRepository;
        this.selectionRepository = selectionRepository;
        this.botRepository = botRepository;
    }

    @Transactional(readOnly = true)
    public List<WorkflowConfiguration> findAll() {
        return configurationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowConfiguration> findById(Long id) {
        return configurationRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowConfiguration> findDefault() {
        return configurationRepository.findByDefaultEntryTrue();
    }

    public WorkflowConfiguration save(WorkflowConfiguration configuration) {
        if (configuration.getName() == null || configuration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        configuration.setName(configuration.getName().trim());

        if (configuration.getId() != null) {
            WorkflowConfiguration existing = configurationRepository.findById(configuration.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
            if (existing.isDefaultEntry()) {
                if (!existing.getName().equals(configuration.getName())) {
                    throw new IllegalArgumentException("The default workflow configuration cannot be renamed");
                }
                configuration.setDefaultEntry(true);
            }
        } else {
            // New configurations are never default; the default is bootstrapped once.
            configuration.setDefaultEntry(false);
        }

        boolean duplicateName = configuration.getId() == null
                ? configurationRepository.existsByName(configuration.getName())
                : configurationRepository.existsByNameAndIdNot(configuration.getName(), configuration.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("A workflow configuration with this name already exists");
        }
        return configurationRepository.save(configuration);
    }

    /**
     * Creates an unsaved deep copy of the given configuration with a unique
     * {@code "Copy of …"} name and identical workflow selections. The caller
     * persists the result via {@link #save(WorkflowConfiguration)} or the
     * controller's create flow.
     */
    public WorkflowConfiguration cloneConfiguration(Long sourceId) {
        WorkflowConfiguration source = configurationRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
        WorkflowConfiguration clone = new WorkflowConfiguration();
        clone.setName(uniqueCopyName(source.getName()));
        clone.setDefaultEntry(false);
        List<WorkflowSelection> clonedSelections = new ArrayList<>();
        for (WorkflowSelection original : source.getSelectedWorkflows()) {
            WorkflowSelection copy = new WorkflowSelection();
            copy.setConfiguration(clone);
            copy.setWorkflowKey(original.getWorkflowKey());
            copy.replaceParams(original.getParamsMap());
            clonedSelections.add(copy);
        }
        clone.setSelectedWorkflows(clonedSelections);
        return clone;
    }

    public void deleteById(Long id) {
        WorkflowConfiguration configuration = configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
        if (configuration.isDefaultEntry()) {
            throw new IllegalStateException("The default workflow configuration cannot be deleted");
        }
        List<Bot> bots = botRepository.findByWorkflowConfigurationId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("Workflow configuration is used by bot(s): " + botNames);
        }
        selectionRepository.deleteByConfigurationId(id);
        configurationRepository.delete(configuration);
    }

    private String uniqueCopyName(String baseName) {
        String candidate = "Copy of " + baseName;
        int suffix = 2;
        while (configurationRepository.existsByName(candidate)) {
            candidate = "Copy of " + baseName + " (" + suffix + ")";
            suffix++;
        }
        return candidate;
    }
}

