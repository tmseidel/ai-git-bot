package org.remus.giteabot.prworkflow.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ReviewWorkflow} preserves the legacy behaviour of
 * {@code BotWebhookService#reviewPullRequest(Bot, WebhookPayload)} byte-for-byte:
 * delegate to {@link CodeReviewService}, then forward the
 * {@link org.remus.giteabot.admin.GitIntegration#getPostReviewAction()} if and
 * only if a review was actually posted.
 */
@ExtendWith(MockitoExtension.class)
class ReviewWorkflowTest {

    @Mock private CodeReviewServiceFactory factory;
    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private RepositoryApiClient repoClient;
    @Mock private CodeReviewService codeReviewService;

    private ReviewWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new ReviewWorkflow(factory, giteaClientFactory);
        lenient().when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        lenient().when(factory.create(any(Bot.class), eq(repoClient))).thenReturn(codeReviewService);
    }

    @Test
    void identifiesAsReviewCategory() {
        assertEquals(ReviewWorkflow.KEY, workflow.key());
        assertEquals("PR Review", workflow.displayName());
        assertEquals(PrWorkflowCategory.REVIEW, workflow.category());
    }

    @Test
    void successWhenCodeReviewServicePostedReview() {
        Bot bot = botWith(PostReviewAction.NONE);
        WebhookPayload payload = payloadFor("acme", "web", 7L);
        when(codeReviewService.reviewPullRequest(eq(payload), eq(null))).thenReturn(true);

        WorkflowResult result = workflow.run(ctx(bot, payload));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(repoClient).postReviewAction(eq("acme"), eq("web"), eq(7L), eq(PostReviewAction.NONE));
    }

    @Test
    void skippedWhenCodeReviewServiceReportedNoReview() {
        Bot bot = botWith(PostReviewAction.APPROVE);
        WebhookPayload payload = payloadFor("acme", "web", 8L);
        when(codeReviewService.reviewPullRequest(eq(payload), eq(null))).thenReturn(false);

        WorkflowResult result = workflow.run(ctx(bot, payload));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        verify(repoClient, never()).postReviewAction(any(), any(), anyLong(), any());
    }

    private static PrWorkflowContext ctx(Bot bot, WebhookPayload payload) {
        return new PrWorkflowContext(bot, payload, 1L, (name, log) -> { /* no-op */ });
    }

    private static Bot botWith(PostReviewAction action) {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("ai_bot");
        bot.setUsername("ai_bot");
        org.remus.giteabot.admin.GitIntegration git = new org.remus.giteabot.admin.GitIntegration();
        git.setPostReviewAction(action);
        bot.setGitIntegration(git);
        return bot;
    }

    private static WebhookPayload payloadFor(String owner, String repo, long prNumber) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin(owner);
        repository.setOwner(user);
        repository.setName(repo);
        payload.setRepository(repository);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        payload.setPullRequest(pr);
        return payload;
    }
}


