package org.remus.giteabot.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.ai.AiRetryContext;
import org.remus.giteabot.repository.RepositoryApiClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRetryNoticesTest {

    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private RepositoryApiClient repositoryApiClient;

    private WorkflowRetryNotices notices;
    private Bot bot;

    @BeforeEach
    void setUp() {
        notices = new WorkflowRetryNotices(giteaClientFactory);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setGitIntegration(new GitIntegration());
    }

    @AfterEach
    void clearRetryContext() {
        AiRetryContext.clear();
    }

    @Test
    void installForPullRequest_pointsTheNoticeAtThePullRequest() {
        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);

        notices.installForPullRequest(bot, "agentic-review", "o", "r", 7L);

        AiRetryContext.Notice notice = AiRetryContext.notice();
        assertNotNull(notice);
        assertEquals("agentic-review", notice.label());
        notice.sink().post("retry scheduled");
        verify(repositoryApiClient).postPullRequestComment("o", "r", 7L, "retry scheduled");
    }

    @Test
    void installForIssue_pointsTheNoticeAtTheIssue() {
        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);

        notices.installForIssue(bot, "issue-x", "o", "r", 12L);

        AiRetryContext.Notice notice = AiRetryContext.notice();
        assertNotNull(notice);
        notice.sink().post("retry scheduled");
        verify(repositoryApiClient).postIssueComment("o", "r", 12L, "retry scheduled");
    }

    @Test
    void incompleteCoordinates_installNoNotice() {
        notices.installForPullRequest(bot, "agentic-review", "o", "r", null);

        assertNull(AiRetryContext.notice());
        verifyNoInteractions(giteaClientFactory);
    }

    @Test
    void unresolvableClient_installsNoNoticeAndDoesNotThrow() {
        when(giteaClientFactory.getApiClient(any()))
                .thenThrow(new IllegalArgumentException("No repository provider registered for type: null"));

        assertDoesNotThrow(() -> notices.installForIssue(bot, "issue-x", "o", "r", 12L));

        assertNull(AiRetryContext.notice());
    }
}
