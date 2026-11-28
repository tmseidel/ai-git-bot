package org.remus.giteabot.workflowrun;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.repository.RepositoryPlatform;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriggerLinksTest {

    private final BotRepository botRepository = mock(BotRepository.class);
    private final RepositoryPlatformFactory platformFactory = mock(RepositoryPlatformFactory.class);
    private final RepositoryPlatform platform = mock(RepositoryPlatform.class);
    private final TriggerLinks links = new TriggerLinks(botRepository, platformFactory);

    @Test
    void pullRequestsAndIssuesLinkThroughTheBotsGitIntegration() {
        Bot bot = bot(1L);
        when(botRepository.findAllById(any())).thenReturn(List.of(bot));
        when(platformFactory.getPlatform(bot.getGitIntegration())).thenReturn(platform);
        when(platform.getPullRequestWebUrl("owner", "repo", 42L)).thenReturn(Optional.of("https://git/pr/42"));
        when(platform.getIssueWebUrl("owner", "repo", 7L)).thenReturn(Optional.of("https://git/issues/7"));

        WorkflowRun pullRequest = run(10L, 1L, TriggerKind.PULL_REQUEST, 42L);
        WorkflowRun issue = run(11L, 1L, TriggerKind.ISSUE, 7L);

        assertThat(links.urlsOf(List.of(pullRequest, issue)))
                .containsEntry(10L, "https://git/pr/42")
                .containsEntry(11L, "https://git/issues/7");
    }

    @Test
    void runsOfDeletedBotsOrBrokenIntegrationsHaveNoLink() {
        Bot broken = bot(2L);
        when(botRepository.findAllById(any())).thenReturn(List.of(broken));
        when(platformFactory.getPlatform(broken.getGitIntegration())).thenThrow(new IllegalStateException("no token"));

        assertThat(links.urlsOf(List.of(run(10L, 1L, TriggerKind.PULL_REQUEST, 42L),
                run(11L, 2L, TriggerKind.PULL_REQUEST, 42L),
                run(12L, null, TriggerKind.PULL_REQUEST, 42L),
                run(13L, 2L, null, 42L)))).isEmpty();
    }

    private static Bot bot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setGitIntegration(new GitIntegration());
        return bot;
    }

    private static WorkflowRun run(Long id, Long botId, TriggerKind kind, Long number) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setBotId(botId);
        run.setRepoOwner("owner");
        run.setRepoName("repo");
        run.setTriggerKind(kind);
        run.setTriggerNumber(number);
        return run;
    }
}
