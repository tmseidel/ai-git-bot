package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CRUD + clone for reusable bot built-in tool configurations. Analogous to
 * {@link McpConfigurationService}.
 *
 * <p>The default configuration is protected: it cannot be renamed, cannot be
 * deleted, and cannot lose its default flag. Its row and initial tool
 * selections are created by Flyway migration V12; admins manage the
 * selection from then on via the System settings UI.</p>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BotToolConfigurationService {

    private final BotToolConfigurationRepository configurationRepository;
    private final BotToolSelectionRepository selectionRepository;
    private final BotRepository botRepository;

    @Transactional(readOnly = true)
    public List<BotToolConfiguration> findAll() {
        return configurationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<BotToolConfiguration> findById(Long id) {
        return configurationRepository.findById(id);
    }

    /**
     * Adds every catalog tool the configuration does not yet select.
     *
     * <p>Used at startup for the default configuration: the Flyway seed can only list the
     * tools that existed when it was written, and the default configuration cannot be
     * edited through the UI, so without this step a bot that never customised its tool
     * set would silently lose every tool added after the seed.</p>
     *
     * @return the number of tools added; {@code 0} when the configuration is complete
     */
    @Transactional
    public int addMissingTools(Long configurationId, List<BuiltinToolRegistry.BuiltinTool> catalog) {
        BotToolConfiguration configuration = configurationRepository.findById(configurationId)
                .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
        Set<String> present = configuration.getSelectedTools().stream()
                .map(BotToolSelection::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int added = 0;
        for (BuiltinToolRegistry.BuiltinTool tool : catalog) {
            if (present.contains(tool.name())) {
                continue;
            }
            BotToolSelection selection = new BotToolSelection();
            selection.setConfiguration(configuration);
            selection.setToolName(tool.name());
            selection.setToolKind(tool.kind().name());
            configuration.getSelectedTools().add(selection);
            added++;
        }
        if (added > 0) {
            configurationRepository.save(configuration);
        }
        return added;
    }

    @Transactional(readOnly = true)
    public Optional<BotToolConfiguration> findDefault() {
        return configurationRepository.findByDefaultEntryTrue();
    }

    public BotToolConfiguration save(BotToolConfiguration configuration) {
        if (configuration.getName() == null || configuration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        configuration.setName(configuration.getName().trim());

        BotToolConfiguration target = configuration;
        if (configuration.getId() != null) {
            BotToolConfiguration existing = configurationRepository.findById(configuration.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
            if (existing.isDefaultEntry()) {
                if (!existing.getName().equals(configuration.getName())) {
                    throw new IllegalArgumentException("The default tool configuration cannot be renamed");
                }
            }
            existing.setName(configuration.getName());
            // Preserve the managed entity and its selectedTools collection.
            target = existing;
        } else {
            // New configurations are never default; the default is bootstrapped once.
            configuration.setDefaultEntry(false);
        }

        boolean duplicateName = configuration.getId() == null
                ? configurationRepository.existsByName(configuration.getName())
                : configurationRepository.existsByNameAndIdNot(configuration.getName(), configuration.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("A tool configuration with this name already exists");
        }
        return configurationRepository.save(target);
    }

    /**
     * Creates an unsaved deep copy of the given configuration with a unique
     * {@code "Copy of …"} name and identical tool selections. Caller is
     * expected to persist via {@link #save(BotToolConfiguration)} after any
     * further edits, or via the controller's create flow.
     */
    public BotToolConfiguration cloneConfiguration(Long sourceId) {
        BotToolConfiguration source = configurationRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
        BotToolConfiguration clone = new BotToolConfiguration();
        clone.setName(uniqueCopyName(source.getName()));
        clone.setDefaultEntry(false);
        List<BotToolSelection> clonedSelections = new ArrayList<>();
        for (BotToolSelection original : source.getSelectedTools()) {
            BotToolSelection copy = new BotToolSelection();
            copy.setConfiguration(clone);
            copy.setToolName(original.getToolName());
            copy.setToolKind(original.getToolKind());
            clonedSelections.add(copy);
        }
        clone.setSelectedTools(clonedSelections);
        return clone;
    }

    public void deleteById(Long id) {
        BotToolConfiguration configuration = configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
        if (configuration.isDefaultEntry()) {
            throw new IllegalStateException("The default tool configuration cannot be deleted");
        }
        List<Bot> bots = botRepository.findByToolConfigurationId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("Tool configuration is used by bot(s): " + botNames);
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


