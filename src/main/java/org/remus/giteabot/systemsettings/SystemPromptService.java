package org.remus.giteabot.systemsettings;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class SystemPromptService {

    private final SystemPromptRepository systemPromptRepository;
    private final BotRepository botRepository;

    public SystemPromptService(SystemPromptRepository systemPromptRepository, BotRepository botRepository) {
        this.systemPromptRepository = systemPromptRepository;
        this.botRepository = botRepository;
    }

    @Transactional(readOnly = true)
    public List<SystemPrompt> findAll() {
        return systemPromptRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<SystemPrompt> findById(Long id) {
        return systemPromptRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<SystemPrompt> findDefault() {
        return systemPromptRepository.findByDefaultEntryTrue();
    }

    public SystemPrompt save(SystemPrompt systemPrompt) {
        if (systemPrompt.getName() == null || systemPrompt.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        systemPrompt.setName(systemPrompt.getName().trim());
        if (systemPrompt.getReviewSystemPrompt() == null || systemPrompt.getReviewSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("Review System-Prompt is required");
        }
        if (systemPrompt.getIssueAgentSystemPrompt() == null || systemPrompt.getIssueAgentSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("Issue-Agent System-Prompt is required");
        }
        if (systemPrompt.getWriterAgentSystemPrompt() == null || systemPrompt.getWriterAgentSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("Writer-Agent System-Prompt is required");
        }
        if (systemPrompt.getE2ePlannerSystemPrompt() == null || systemPrompt.getE2ePlannerSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("E2E Planner System-Prompt is required");
        }
        if (systemPrompt.getE2eAuthorSystemPrompt() == null || systemPrompt.getE2eAuthorSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("E2E Author System-Prompt is required");
        }
        if (systemPrompt.getE2eRunnerSystemPrompt() == null || systemPrompt.getE2eRunnerSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("E2E Runner System-Prompt is required");
        }
        boolean duplicateName = systemPrompt.getId() == null
                ? systemPromptRepository.existsByName(systemPrompt.getName())
                : systemPromptRepository.existsByNameAndIdNot(systemPrompt.getName(), systemPrompt.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("A system prompt with this name already exists");
        }
        if (systemPrompt.isDefaultEntry()) {
            Optional<SystemPrompt> existingDefault = systemPromptRepository.findByDefaultEntryTrue();
            if (existingDefault.isPresent() && !Objects.equals(existingDefault.get().getId(), systemPrompt.getId())) {
                throw new IllegalArgumentException("Only one default system prompt is allowed");
            }
        }
        return systemPromptRepository.save(systemPrompt);
    }

    public void deleteById(Long id) {
        SystemPrompt systemPrompt = systemPromptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("System prompt not found"));
        if (systemPrompt.isDefaultEntry()) {
            throw new IllegalStateException("The default system prompt cannot be deleted");
        }
        List<Bot> bots = botRepository.findBySystemPromptId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("System prompt is used by bot(s): " + botNames);
        }
        systemPromptRepository.delete(systemPrompt);
    }

}
