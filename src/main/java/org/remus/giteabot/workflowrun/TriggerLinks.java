package org.remus.giteabot.workflowrun;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Links runs to their pull request or issue in the Git provider, through the run's bot and its
 * Git integration ({@link RepositoryPlatformFactory} caches the platforms). A run whose bot was
 * deleted, or whose provider has no page for the trigger, gets no link.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TriggerLinks {

    private final BotRepository botRepository;
    private final RepositoryPlatformFactory platformFactory;

    /** The pull request or issue page of each run that has one, by run id; the bots are loaded at once. */
    Map<Long, String> urlsOf(Collection<WorkflowRun> runs) {
        Map<Long, Bot> bots = botRepository.findAllById(runs.stream().map(WorkflowRun::getBotId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Bot::getId, Function.identity()));
        Map<Long, String> urls = new HashMap<>();
        for (WorkflowRun run : runs) {
            Bot bot = run.getBotId() != null ? bots.get(run.getBotId()) : null;
            if (bot != null && run.getTriggerKind() != null) {
                urlOf(bot, run).ifPresent(url -> urls.put(run.getId(), url));
            }
        }
        return urls;
    }

    private Optional<String> urlOf(Bot bot, WorkflowRun run) {
        try {
            RepositoryPlatform platform = platformFactory.getPlatform(bot.getGitIntegration());
            return run.getTriggerKind() == TriggerKind.PULL_REQUEST
                    ? platform.getPullRequestWebUrl(run.getRepoOwner(), run.getRepoName(), run.getTriggerNumber())
                    : platform.getIssueWebUrl(run.getRepoOwner(), run.getRepoName(), run.getTriggerNumber());
        } catch (RuntimeException e) {
            // A misconfigured integration costs the link, not the page.
            log.debug("No Git platform for bot {}: {}", bot.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
