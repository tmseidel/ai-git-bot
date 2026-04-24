package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.session.SessionService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotWebhookServiceTest {

    @Mock private AiClientFactory aiClientFactory;
    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private PromptService promptService;
    @Mock private SessionService sessionService;
    @Mock private AgentConfigProperties agentConfig;
    @Mock private AgentSessionService agentSessionService;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private WorkspaceService workspaceService;
    @Mock private DiffApplyService diffApplyService;
    @Mock private BotService botService;
    @Mock private RepositoryApiClient repositoryApiClient;

    private BotWebhookService botWebhookService;

    @BeforeEach
    void setUp() {
        botWebhookService = new BotWebhookService(aiClientFactory, giteaClientFactory,
                promptService, sessionService, agentConfig, new ReviewConfigProperties(),
                agentSessionService, toolExecutionService, workspaceService, diffApplyService, botService);
    }

    // ---- isBotUser tests ----

    @Test
    void isBotUser_senderMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("ai_bot");
        payload.setSender(sender);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_senderDoesNotMatch_returnsFalse() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_nullUsername_returnsFalse() {
        Bot bot = createBot("test-bot", null, false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_commentUserMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin("ai_bot");
        comment.setUser(user);
        payload.setComment(comment);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    // ---- getBotAlias tests ----

    @Test
    void getBotAlias_returnsMentionFormat() {
        Bot bot = createBot("test-bot", "ai_bot", false);
        assertEquals("@ai_bot", botWebhookService.getBotAlias(bot));
    }

    @Test
    void getBotAlias_nullUsername_returnsEmpty() {
        Bot bot = createBot("test-bot", null, false);
        assertEquals("", botWebhookService.getBotAlias(bot));
    }

    // ---- handlePrComment routing tests ----

    @Nested
    class HandlePrCommentTests {

        private static final String OWNER = "Test";
        private static final String REPO = "my-repo";
        private static final long PR_NUMBER = 140L;
        private static final long COMMENT_ID = 1055L;

        private WebhookPayload prCommentPayload;

        @BeforeEach
        void setUpPayload() {
            prCommentPayload = buildPrCommentPayload(OWNER, REPO, PR_NUMBER, COMMENT_ID,
                    "@claude_bot please do something");
            // Both factories return the shared repository client mock
            when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
            when(aiClientFactory.getClient(any())).thenReturn(null); // not reached in these tests
        }

        @Test
        void agentSessionFoundByIssueNumber_agentEnabled_routesToAgent() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            when(agentSessionService.toAiMessages(any())).thenReturn(List.of());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Agent path: AgentSessionService.setStatus(UPDATING) must be called
            verify(agentSessionService).setStatus(any(AgentSession.class),
                    eq(AgentSession.AgentSessionStatus.UPDATING));
            // Review path's SessionService.getOrCreateSession must NOT be called
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
        }

        @Test
        void agentSessionFoundByPrNumber_fallback_agentEnabled_routesToAgent() {
            // First lookup (by issue/PR number) finds nothing – second lookup (by PR number) finds session
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            when(agentSessionService.toAiMessages(any())).thenReturn(List.of());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // getSessionByPr is called in handlePrComment AND again inside handleIssueComment
            verify(agentSessionService, atLeastOnce()).getSessionByPr(OWNER, REPO, PR_NUMBER);
            verify(agentSessionService).setStatus(any(AgentSession.class),
                    eq(AgentSession.AgentSessionStatus.UPDATING));
            verify(sessionService, never()).getOrCreateSession(any(), any(), any(), any());
        }

        @Test
        void noAgentSession_routesToCodeReviewHandler() {
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Review path: SessionService.getOrCreateSession must be called
            verify(sessionService).getOrCreateSession(OWNER, REPO, PR_NUMBER, null);
            // Agent path's setStatus must NOT be called
            verify(agentSessionService, never()).setStatus(any(), any());
        }

        @Test
        void agentSessionExists_butAgentDisabled_routesToCodeReviewHandler() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));

            // bot.isAgentEnabled() = false
            botWebhookService.handlePrComment(createBot("bot", "claude_bot", false), prCommentPayload);

            verify(sessionService).getOrCreateSession(OWNER, REPO, PR_NUMBER, null);
            verify(agentSessionService, never()).setStatus(any(), any());
        }

        @Test
        void noAgentSession_issueNumberLookupCalledBeforePrNumberLookup() {
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());
            when(agentSessionService.getSessionByPr(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.empty());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Verify lookup order: issue first, PR second
            var inOrder = inOrder(agentSessionService);
            inOrder.verify(agentSessionService).getSessionByIssue(OWNER, REPO, PR_NUMBER);
            inOrder.verify(agentSessionService).getSessionByPr(OWNER, REPO, PR_NUMBER);
        }

        @Test
        void agentSessionFoundByIssueNumber_prLookupIsSkipped() {
            AgentSession session = agentSession(OWNER, REPO, PR_NUMBER);
            when(agentSessionService.getSessionByIssue(OWNER, REPO, PR_NUMBER))
                    .thenReturn(Optional.of(session));
            when(agentSessionService.toAiMessages(any())).thenReturn(List.of());

            botWebhookService.handlePrComment(createBot("bot", "claude_bot", true), prCommentPayload);

            // Short-circuit: PR-number lookup must not be called
            verify(agentSessionService, never()).getSessionByPr(any(), any(), any());
        }
    }

    // ---- helpers ----

    private Bot createBot(String name, String username, boolean agentEnabled) {
        Bot bot = new Bot();
        bot.setName(name);
        bot.setUsername(username);
        bot.setAgentEnabled(agentEnabled);
        return bot;
    }

    /** Overload kept for backward-compat with the existing tests above. */
    private Bot createBot(String name, String username) {
        return createBot(name, username, false);
    }

    private AgentSession agentSession(String owner, String repo, long issueNumber) {
        AgentSession s = new AgentSession(owner, repo, issueNumber, "test issue");
        s.setPrNumber(issueNumber); // PR created from this issue
        return s;
    }

    /**
     * Builds a {@link WebhookPayload} that simulates a comment on a PR discussion thread,
     * matching the Gitea webhook structure observed in production.
     */
    private WebhookPayload buildPrCommentPayload(String owner, String repo,
                                                  long prNumber, long commentId,
                                                  String commentBody) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        // Sender
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("tom");
        payload.setSender(sender);

        // Repository
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        repository.setFullName(owner + "/" + repo);
        WebhookPayload.Owner repoOwner = new WebhookPayload.Owner();
        repoOwner.setLogin(owner);
        repository.setOwner(repoOwner);
        payload.setRepository(repository);

        // Issue (the PR as seen through Gitea's issue model)
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(prNumber);
        issue.setTitle("Some PR");
        issue.setBody("");
        WebhookPayload.IssuePullRequest issuePr = new WebhookPayload.IssuePullRequest();
        issuePr.setMerged(false);
        issue.setPullRequest(issuePr);
        payload.setIssue(issue);

        // Top-level pull_request (distinguishes PR comment from plain issue comment)
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        pr.setId(80L);
        pr.setState("open");
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/branch");
        pr.setHead(head);
        WebhookPayload.Head base = new WebhookPayload.Head();
        base.setRef("main");
        pr.setBase(base);
        payload.setPullRequest(pr);

        // Comment
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(commentId);
        comment.setBody(commentBody);
        WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
        commentUser.setLogin("tom");
        comment.setUser(commentUser);
        payload.setComment(comment);

        return payload;
    }
}


