package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.packagemanager.PackageManagerDetector;
import org.remus.giteabot.agent.packagemanager.YarnCommandBuilder;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.PullRequestWorkspaceMode;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.bitbucket.BitbucketApiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.learning.context.ActiveLessons;
import org.remus.giteabot.learning.context.LearningContextProvider;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LessonUsage;
import org.remus.giteabot.learning.knowledge.LessonUsageRepository;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.TestPlatforms;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.review.structured.DraftSnapshot;
import org.remus.giteabot.review.structured.ReferenceAllocator;
import org.remus.giteabot.review.structured.ReferenceNamespace;
import org.remus.giteabot.review.structured.ReviewCommentRenderer;
import org.remus.giteabot.review.structured.ReviewRecord;
import org.remus.giteabot.review.structured.ReviewRecordRepository;
import org.remus.giteabot.review.structured.ReviewPublicationState;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.ReviewSubmissionToolExecutor;
import org.remus.giteabot.review.structured.ReviewToolOperation;
import org.remus.giteabot.review.structured.ReviewToolOperationRepository;
import org.remus.giteabot.review.structured.ReviewToolOperationService;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.remus.giteabot.review.structured.WorkflowOutputRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The agentic review is published from the recorded draft, never from model prose. These tests
 * drive the whole path — strategy, submission tools, persistence, rendering and publication — with
 * stubbed AI/provider clients, so no live model or paid call is involved.
 */
@ExtendWith(MockitoExtension.class)
class AgentReviewCompletionTest {

    private static final Path WORKSPACE = Path.of("/tmp/agent-review-test");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIFF = """
            diff --git a/Example.java b/Example.java
            --- a/Example.java
            +++ b/Example.java
            @@ -1 +1 @@
            -old
            +new
            """;

    @Mock private AiClient aiClient;
    @Mock private RepositoryApiClient repositoryClient;
    private RepositoryPlatform platform;
    @Mock private WorkspaceService workspaceService;
    @Mock private Workspace workspace;
    @Mock private EventHookPublisher eventHooks;
    @Mock private WorkflowOutputRepository outputRepository;
    @Mock private ReviewRecordRepository reviewRepository;
    @Mock private ReferenceAllocator referenceAllocator;
    @Mock private ReviewToolOperationRepository operationRepository;
    private final org.remus.giteabot.config.ReviewCommentProperties commentProperties =
            new org.remus.giteabot.config.ReviewCommentProperties();
    @Mock private org.remus.giteabot.review.structured.ReviewPublicationRepository publicationRepository;
    @Mock private org.remus.giteabot.review.structured.WorkflowOutputSnapshotRepository snapshotRepository;

    private final Map<Long, ReviewRecord> storedReviews = new HashMap<>();
    private final Map<String, org.remus.giteabot.review.structured.WorkflowOutput> storedOutputs = new HashMap<>();
    private final Map<String, ReviewToolOperation> receipts = new HashMap<>();
    private final Map<Long, org.remus.giteabot.review.structured.ReviewPublication> publications =
            new HashMap<>();

    @Mock private KnowledgeLessonService lessonService;
    @Mock private LessonUsageRepository usageRepository;
    private AgentReviewLessons lessons = AgentReviewLessons.NONE;
    private AgentReviewLearningSettings currentLearning = CURATED;

    private AgentReviewService service;
    private StructuredReviewService reviewService;
    private WebhookPayload payload;
    private AgentConfigProperties config;

    @BeforeEach
    void setUp() {
        platform = TestPlatforms.of(repositoryClient);
        lenient().when(repositoryClient.getAuthenticatedUser(any())).thenReturn(TestPlatforms.USER);
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("repo");
        repository.setOwner(owner);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature");
        head.setSha("head-sha");
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(1L);
        pullRequest.setTitle("Update example");
        pullRequest.setHead(head);
        payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setPullRequest(pullRequest);

        config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(12);

        reviewService = new StructuredReviewService(outputRepository, reviewRepository, referenceAllocator);
        lenient().when(outputRepository.save(any())).thenAnswer(invocation -> {
            org.remus.giteabot.review.structured.WorkflowOutput output = invocation.getArgument(0);
            if (output.getId() == null) {
                output.setId((long) storedOutputs.size() + 1);
            }
            storedOutputs.put(output.getReference(), output);
            return output;
        });
        lenient().when(outputRepository.findByReference(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(storedOutputs.get(invocation.getArgument(0))));
        lenient().when(reviewRepository.findByOutputId(anyLong())).thenAnswer(invocation -> storedReviews
                .values().stream()
                .filter(record -> record.getOutput() != null
                        && record.getOutput().getId().equals(invocation.getArgument(0)))
                .findFirst());
        lenient().when(reviewRepository.findForUpdateByOutputId(anyLong()))
                .thenAnswer(invocation -> reviewRepository.findByOutputId(invocation.getArgument(0)));
        lenient().when(reviewRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
            ReviewRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId((long) storedReviews.size() + 1);
            }
            storedReviews.put(record.getId(), record);
            return record;
        });
        lenient().when(reviewRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(storedReviews.get(invocation.getArgument(0))));
        lenient().when(referenceAllocator.next(ReferenceNamespace.OUTPUT)).thenReturn("O1234");
        lenient().when(operationRepository.findByReviewIdAndOperationId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        receipts.get(invocation.getArgument(0) + "|" + invocation.getArgument(1))));
        lenient().when(operationRepository.save(any(ReviewToolOperation.class))).thenAnswer(invocation -> {
            ReviewToolOperation receipt = invocation.getArgument(0);
            receipts.put(receipt.getReviewId() + "|" + receipt.getOperationId(), receipt);
            return receipt;
        });

        lenient().when(publicationRepository.save(any())).thenAnswer(invocation -> {
            org.remus.giteabot.review.structured.ReviewPublication publication = invocation.getArgument(0);
            if (publication.getId() == null) {
                publication.setId((long) publications.size() + 1);
            }
            publications.put(publication.getId(), publication);
            return publication;
        });
        lenient().when(publicationRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(publications.get(invocation.getArgument(0))));
        lenient().when(publicationRepository.findByReviewIdOrderByRenderRevisionAsc(anyLong()))
                .thenAnswer(invocation -> publications.values().stream()
                        .filter(publication -> publication.getReviewId().equals(invocation.getArgument(0)))
                        .sorted(java.util.Comparator.comparingInt(
                                org.remus.giteabot.review.structured.ReviewPublication::getRenderRevision))
                        .toList());
        lenient().when(publicationRepository.findTopByReviewIdOrderByRenderRevisionDesc(anyLong()))
                .thenAnswer(invocation -> publications.values().stream()
                        .filter(publication -> publication.getReviewId().equals(invocation.getArgument(0)))
                        .max(java.util.Comparator.comparingInt(
                                org.remus.giteabot.review.structured.ReviewPublication::getRenderRevision)));
        lenient().when(snapshotRepository.findByReviewId(anyLong())).thenReturn(Optional.empty());
        lenient().when(outputRepository.findByScope(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyString(), any())).thenReturn(java.util.List.of());
        lenient().when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = serviceFor(platform, RepositoryType.GITEA);
        lenient().when(repositoryClient.resolveCommitSha(any(), any())).thenCallRealMethod();
        lenient().when(aiClient.supportsNativeTools()).thenReturn(true);
        lenient().when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF);
        lenient().when(workspaceService.preparePullRequestWorkspace(platform, "owner", "repo",
                        "feature", 1L, PullRequestWorkspaceMode.READ_ONLY))
                .thenReturn(WorkspaceResult.success(workspace));
        lenient().when(workspace.path()).thenReturn(WORKSPACE);
    }

    private AgentReviewService serviceFor(RepositoryPlatform platform, RepositoryType type) {
        ToolCatalog catalog = new ToolCatalog(config);
        ReviewSubmissionToolExecutor submissionExecutor = new ReviewSubmissionToolExecutor(
                reviewService, new ReviewToolOperationService(operationRepository));
        org.remus.giteabot.review.structured.ReviewPublicationService publicationService =
                new org.remus.giteabot.review.structured.ReviewPublicationService(publicationRepository,
                        outputRepository, reviewRepository);
        org.remus.giteabot.review.structured.ReviewSnapshotService snapshotService =
                new org.remus.giteabot.review.structured.ReviewSnapshotService(snapshotRepository,
                        outputRepository, reviewRepository, new org.remus.giteabot.config.LearningProperties());
        ReviewPublisher publisher = new ReviewPublisher(reviewService, publicationService,
                new ReviewCommentRenderer(new org.remus.giteabot.config.ReviewCommentProperties()), eventHooks);

        Bot bot = new Bot();
        bot.setId(3L);
        GitIntegration git = new GitIntegration();
        git.setId(7L);
        git.setProviderType(type);
        bot.setGitIntegration(git);

        return new AgentReviewService(
                new AgentReviewContext(platform, aiClient, "Review the change.",
                        null, null, Set.of("pr-diff"), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, new PackageManagerDetector(),
                        new YarnCommandBuilder(config), new org.remus.giteabot.agent.filetransfer.FileTransferService(config)), catalog, workspaceService,
                config, null, bot, eventHooks, null, reviewService, submissionExecutor, snapshotService,
                new org.remus.giteabot.review.structured.ReviewLookupToolExecutor(
                        new org.remus.giteabot.review.structured.PublishedReviewLookup(
                                outputRepository, reviewRepository)),
                commentProperties,
                publisher,
                lessons);
    }

    // ------------------------------------------------------------------ structured publication

    @Test
    void publishesTheReviewTheModelRecordedAndIgnoresItsText() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("# 🤖 AI Review · O1234")
                        && body.contains("⚠️ **Findings present**")
                        && body.contains("🟠 **1** medium")
                        && body.contains("### 🟠 MEDIUM · `O1234-F1` · "
                                + "Settings update may bypass authorization")
                        && body.contains("*Agentic review by [AI Git Bot](https://gitbot.eu)*")),
                eq(PostReviewAction.NONE));
        verify(eventHooks).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED), any(),
                eq("owner"), eq("repo"), eq(1L), isNull(),
                argThat(data -> "O1234".equals(String.valueOf(data.get("review")))
                        && "O1234-F1".equals(String.valueOf(
                                ((Map<?, ?>) data.get("finding")).get("reference")))));
        assertThat(draft().state()).isEqualTo(ReviewState.FINALIZED);
    }

    @Test
    void aTextOnlyReplyIsNeverPublishedAndGivesUpAfterBoundedReminders() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("Looks good to me."),
                        ChatTurn.text("Still looks good."),
                        ChatTurn.text("Final answer: approve."));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);

        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        assertThat(draft().state()).isEqualTo(ReviewState.FAILED);
        assertThat(draft().findings()).isEmpty();
    }

    @Test
    void aCompletionThatSharesItsBatchIsRefusedAndNothingIsFinalized() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(mixedCompletionTurn(), ChatTurn.text("I am done."),
                        ChatTurn.text("I am still done."), ChatTurn.text("Done."));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertThat(draft().state()).isEqualTo(ReviewState.FAILED);
        assertThat(draft().findings()).isEmpty();
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void theFormalDecisionIsComputedFromPersistedFindingsAndThresholds() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth", "BLOCKER"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(service.reviewPullRequest(payload, 12, true,
                new AgentReviewService.SeverityThresholds(0, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L), anyString(),
                eq(PostReviewAction.REQUEST_CHANGES));
    }

    @Test
    void aFailedFormalSubmissionFallsBackToAPlainCommentOfTheSameReview() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));
        doThrow(new IllegalStateException("review API rejected the submission"))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThat(service.reviewPullRequest(payload, 12, true,
                new AgentReviewService.SeverityThresholds(0, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReviewComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("AI Review · O1234")));
        assertThat(draft().state()).isEqualTo(ReviewState.FINALIZED);
    }

    @Test
    void updatesOneProgressCommentAndPublishesTheReviewSeparately() {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                contains("Agentic Review Progress"))).thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(service.reviewPullRequest(payload, 12, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true, AgentReviewLearningSettings.DISABLED))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("Preparing repository")));
        verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L),
                eq(CommentId.NumericCommentId.of(42L)),
                argThat(body -> body.contains("- Latest activity: Completed")));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "true, true"})
    void comparesBitbucketWebhookAndApiHeadsAfterResolvingForkCommits(boolean moved, boolean abbreviatedCurrent) {
        String oldPrefix = "abc123456789";
        String oldSha = oldPrefix + "a".repeat(28);
        String newPrefix = "def123456789";
        // A full current SHA can share the old prefix without being the reviewed commit.
        String newSha = (abbreviatedCurrent ? newPrefix : oldPrefix) + "b".repeat(28);
        String basePrefix = "789abc123456";
        String baseSha = basePrefix + "c".repeat(28);
        String api = "https://api.bitbucket.org/2.0";
        String pr = api + "/repositories/owner/repo/pullrequests/1";
        String forkCommits = api + "/repositories/contributor/fork/commit/";
        RestClient.Builder builder = RestClient.builder().baseUrl(api);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var adapter = new BitbucketApiClient(builder.build(), "https://bitbucket.org");
        RepositoryApiClient bitbucketClient = mock(RepositoryApiClient.class, delegatesTo(adapter));
        doReturn(TestPlatforms.USER).when(bitbucketClient).getAuthenticatedUser(any());
        var bitbucketPlatform = TestPlatforms.of(bitbucketClient);
        var bitbucket = serviceFor(bitbucketPlatform, RepositoryType.BITBUCKET);
        payload.getPullRequest().getHead().setSha(oldPrefix);
        payload.getPullRequest().getHead().setRepositoryFullName("contributor/fork");
        var base = new WebhookPayload.Head();
        base.setRef("main");
        base.setSha(basePrefix);
        base.setRepositoryFullName("owner/repo");
        payload.getPullRequest().setBase(base);
        server.expect(requestTo(forkCommits + oldPrefix)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(bitbucketCommit(oldSha, "contributor/fork"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(api + "/repositories/owner/repo/commit/" + basePrefix))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(bitbucketCommit(baseSha, "owner/repo"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(pr + "/diff")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(DIFF, MediaType.TEXT_PLAIN));
        server.expect(ExpectedCount.times(2), requestTo(pr)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"state":"OPEN","source":{"branch":{"name":"feature"},"commit":{"hash":"%s"},
                         "repository":{"full_name":"contributor/fork"}},
                         "destination":{"branch":{"name":"main"},"commit":{"hash":"%s"},
                         "repository":{"full_name":"owner/repo"}}}
                        """.formatted(abbreviatedCurrent ? newPrefix : (moved ? newSha : oldSha), basePrefix),
                        MediaType.APPLICATION_JSON));
        if (abbreviatedCurrent) {
            server.expect(ExpectedCount.times(2), requestTo(forkCommits + newPrefix)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(bitbucketCommit(newSha, "contributor/fork"), MediaType.APPLICATION_JSON));
        }
        if (!moved) {
            server.expect(requestTo(pr + "/comments")).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess());
        }
        when(workspaceService.preparePullRequestWorkspace(bitbucketPlatform, "owner", "repo",
                "feature", 1L, PullRequestWorkspaceMode.READ_ONLY)).thenReturn(WorkspaceResult.success(workspace));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(bitbucket.reviewPullRequest(payload, 12, false, null, 1L, null, false,
                new AgentReviewLearningSettings(true)))
                .isEqualTo(moved ? AgentReviewService.ReviewResult.SUPPRESSED : AgentReviewService.ReviewResult.POSTED);
        assertThat(publications.get(1L).getState())
                .isEqualTo(moved ? ReviewPublicationState.SUPPRESSED : ReviewPublicationState.PUBLISHED);
        assertThat(storedReviews.get(1L).getBaseSha()).isEqualTo(baseSha);
        verify(snapshotRepository).save(argThat(snapshot -> oldSha.equals(snapshot.getHeadSha())
                && baseSha.equals(snapshot.getBaseSha())
                && (snapshot.getIncompleteReason() != null
                && snapshot.getIncompleteReason().contains("head moved")) == moved));
        server.verify();
    }

    private static String bitbucketCommit(String sha, String repository) {
        return """
                {"type":"commit","hash":"%s","repository":{"full_name":"%s"}}
                """.formatted(sha, repository);
    }

    @Test
    void aFailedGenerationClosesItsDraftAndPublishesNothing() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("Model request failed"));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertThat(draft().state()).isEqualTo(ReviewState.FAILED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void feedbackCollectionCapturesTheEvidenceTheReviewUsed() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(service.reviewPullRequest(payload, 12, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false,
                new AgentReviewLearningSettings(true)))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(snapshotRepository).save(argThat(snapshot ->
                "head-sha".equals(snapshot.getHeadSha())
                        && "feature".equals(snapshot.getHeadRef())
                        && snapshot.getDiffText() != null
                        && snapshot.getSystemPrompt() != null
                        && snapshot.getSystemPromptHash() != null
                        && !snapshot.isComplete()));
    }

    @Test
    void theReviewIsUnaffectedWhenFeedbackCollectionIsOff() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(snapshotRepository, never()).save(any());
        assertThat(draft().state()).isEqualTo(ReviewState.FINALIZED);
    }

    // ------------------------------------------------------------------ shared paths

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void emptyTurnDoesNotPublishAnEarlierExplorationMessage(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), reason, 78_886, 16_384));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
    }

    @Test
    void clarificationStillAnswersWithPlainText() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("It changes the greeting."));

        assertThat(service.answerClarification(payload, "Why was this changed?", 5, false))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("## 🤖 Follow-up")
                        && body.contains("It changes the greeting.")
                        && body.endsWith("*Agentic review follow-up by [AI Git Bot]"
                                        + "(https://gitbot.eu)*")));
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void aFollowUpWithoutAConfiguredFooterEndsWithTheAnswer() {
        commentProperties.setClarificationFooter("   ");
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("It changes the greeting."));

        assertThat(service.answerClarification(payload, "Why?", 5, false))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.equals("## 🤖 Follow-up\n\nIt changes the greeting.")));
    }

    @Test
    void aFollowUpPointsAtTheReviewLookupTool() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1),
                        ChatTurn.text("O1234-F1 flags a permissions gap."));
        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        org.mockito.ArgumentCaptor<String> prompts =
                org.mockito.ArgumentCaptor.forClass(String.class);
        assertThat(service.answerClarification(payload, "What does O1234-F1 mean?", 5, false))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        // The follow-up starts empty and is told how to read what was published, instead of being
        // handed a digest that costs tokens on every run and still misses older reviews.
        // Signature: (history, userMessage, tools, systemPrompt, jsonSchema, maxTokens).
        verify(aiClient, atLeastOnce()).chatWithTools(anyList(), prompts.capture(), anyList(),
                anyString(), isNull(), anyInt());
        assertThat(prompts.getAllValues()).anySatisfy(prompt -> assertThat(prompt)
                .contains("review-lookup")
                .contains("Resolve any reference the user cites")
                .doesNotContain("Settings update may bypass authorization"));
    }

    @Test
    void theProgressCommentReportsWhatEachRoundActuallyDid() {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                contains("Agentic Review Progress"))).thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(service.reviewPullRequest(payload, 12, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true,
                AgentReviewLearningSettings.DISABLED))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repositoryClient, atLeastOnce()).updatePullRequestComment(eq("owner"), eq("repo"),
                eq(1L), eq(CommentId.NumericCommentId.of(42L)), bodies.capture());

        // "Waiting for model" is the loop's own line while the AI call is in flight; a completed
        // round must replace it with what that round did, or the comment never leaves that state.
        // Later rounds are throttled to one write per 15s, which a unit-speed test never reaches.
        assertThat(bodies.getAllValues()).anySatisfy(body -> assertThat(body)
                .contains("- Latest activity: recorded a finding"));
        assertThat(bodies.getAllValues()).last().satisfies(body -> assertThat(body)
                .contains("- Latest activity: Completed"));
    }

    @ParameterizedTest
    @CsvSource({"create,false", "activity,false", "final,false",
            "create,true", "activity,true", "final,true"})
    void progressFailuresDoNotChangeSuccessfulOutcome(String failedWrite, boolean clarification) {
        if (failedWrite.equals("create")) {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    contains("Agentic Review Progress")))
                    .thenThrow(new IllegalStateException("Comment creation denied"));
        } else {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    contains("Agentic Review Progress"))).thenReturn(CommentId.NumericCommentId.of(42L));
            doAnswer(invocation -> {
                boolean terminal = invocation.<String>getArgument(4).contains("- Latest activity: Completed");
                if (terminal == failedWrite.equals("final")) {
                    throw new IllegalStateException("Comment update denied");
                }
                return null;
            }).when(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    eq(CommentId.NumericCommentId.of(42L)), anyString());
        }
        if (clarification) {
            when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                    .thenReturn(ChatTurn.text("Clarification answer."));
        } else {
            when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                    .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));
        }

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, true)
                : service.reviewPullRequest(payload, 12, false,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true, AgentReviewLearningSettings.DISABLED);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        if (clarification) {
            verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    argThat(body -> body.contains("Clarification answer.")));
            verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        } else {
            verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                    argThat(body -> body.contains("AI Review · O1234")), eq(PostReviewAction.NONE));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalProgressFailureDoesNotHideTheOriginalFailure(boolean clarification) {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                contains("Agentic Review Progress"))).thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("Model request failed"));
        doThrow(new IllegalStateException("Comment update denied")).when(repositoryClient)
                .updatePullRequestComment(eq("owner"), eq("repo"), eq(1L),
                        eq(CommentId.NumericCommentId.of(42L)),
                        argThat(body -> body.contains("- Latest activity: Failed")));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, true)
                : service.reviewPullRequest(payload, 12, false,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true, AgentReviewLearningSettings.DISABLED);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L),
                eq(CommentId.NumericCommentId.of(42L)),
                argThat(body -> body.contains("- Latest activity: Failed")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clarificationUsesTheSameOptionalProgressSetting(boolean enabled) {
        Bot bot = new Bot();
        GitIntegration git = new GitIntegration();
        git.setProviderType(RepositoryType.GITEA);
        bot.setGitIntegration(git);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(7L);
        bot.setWorkflowConfiguration(configuration);
        WorkflowSelectionService selections = mock(WorkflowSelectionService.class);
        when(selections.resolveParams(7L, "agentic-review"))
                .thenReturn(Map.of("enableProgressComments", enabled));
        AgentReviewServiceFactory factory = mock(AgentReviewServiceFactory.class);
        when(factory.create(bot)).thenReturn(service);
        if (enabled) {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    contains("Agentic Review Progress"))).thenReturn(CommentId.NumericCommentId.of(42L));
        }
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), ChatTurn.text("Clarification answer."));
        PrWorkflowContext context = new PrWorkflowContext(bot, payload, 1L,
                (name, log) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Why was this changed?"), null);

        assertThat(new AgentReviewWorkflow(factory, selections).run(context).status())
                .isEqualTo(WorkflowResultStatus.SUCCESS);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("Clarification answer.")));
        if (enabled) {
            verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    contains("Agentic Review Progress"));
            verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    eq(CommentId.NumericCommentId.of(42L)),
                    argThat(body -> body.contains("- Latest activity: Completed")));
        } else {
            verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                    contains("Agentic Review Progress"));
            verify(repositoryClient, never()).updatePullRequestComment(anyString(), anyString(), anyLong(),
                    any(CommentId.class), anyString());
        }
    }

    @Test
    void exhaustedExplorationBudgetNeverPublishesExplorationText() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), exploration(2), exploration(3));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIncompleteTurnCannotPublishReviewOrClarification(boolean clarification) {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        lenient().when(aiClient.chat(anyList(), any(), anyString(), isNull(), anyInt()))
                .thenReturn("A partial review");
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial review", List.of(), StopReason.MAX_TOKENS, 100, 32));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, false)
                : service.reviewPullRequest(payload, 1, false,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
        verify(aiClient, never()).chat(anyList(), any(), anyString(), isNull(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = StopReason.class, names = {"MAX_TOKENS", "OTHER"})
    void failedTurnCannotExecuteToolsOrPublishPartialText(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial finding", exploration(1).toolCalls(), reason, 100, 32));
        List<AgentRunContext.ToolCallRecord> toolCalls = new ArrayList<>();

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, true,
                new AgentReviewService.SeverityThresholds(0, 0, 0), 1L,
                toolCalls::add, false, AgentReviewLearningSettings.DISABLED);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(toolCalls).isEmpty();
        assertNothingPublished();
    }

    @Test
    void failedClarificationDoesNotPublishItsDiagnosticPayload() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), StopReason.MAX_TOKENS, 100, 32));

        assertThat(service.answerClarification(payload, "Why was this changed?", 1, false))
                .isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertNothingPublished();
    }

    @Test
    void theClosingReminderAsksForSubmissionNotSilence() {
        config.getBudget().setMaxRounds(40);
        List<String> histories = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    List<AiMessage> history = invocation.getArgument(0);
                    histories.add(history.stream()
                            .map(AiMessage::getContent)
                            .filter(content -> content != null)
                            .reduce("", String::concat));
                    return exploration(histories.size());
                });

        assertThat(service.reviewPullRequest(payload, 4, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED))
                .isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertThat(histories.getLast())
                .contains("Stop exploring and")
                .contains("review-complete")
                .doesNotContain("Avoid further tool calls");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingDiffIsANoOpForReviewsAndClarifications(boolean clarification) {
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn("");

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, false)
                : service.reviewPullRequest(payload, 1, false,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.NO_DIFF);
        verifyNoInteractions(aiClient, workspaceService, eventHooks);
    }

    // ------------------------------------------------------------------ legacy transport

    @Test
    void theLegacyTransportRecordsTheReviewFromTypedToolArguments() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(legacyRunTools("r1",
                                ReviewSubmissionToolExecutor.UPSERT_FINDING,
                                findingArguments("auth", "MEDIUM"))),
                        ChatTurn.text(legacyRunTools("r2", ReviewSubmissionToolExecutor.SET_ASSESSMENT,
                                assessmentArguments(1))),
                        ChatTurn.text(legacyRunTools("r3", ReviewSubmissionToolExecutor.COMPLETE,
                                completionArguments(2, 1))));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("AI Review · O1234")
                        && body.contains("`O1234-F1`")),
                eq(PostReviewAction.NONE));
        assertThat(draft().state()).isEqualTo(ReviewState.FINALIZED);
    }

    @Test
    void aLegacyTextOnlyReplyIsNeverPublished() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("The change looks fine to me."),
                        ChatTurn.text("Still fine."),
                        ChatTurn.text("Fine."));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        assertThat(draft().state()).isEqualTo(ReviewState.FAILED);
    }

    @Test
    void aLegacyTurnThatMixesReviewAndExplorationCallsAppliesNothing() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        String mixed = "{\"runTools\":["
                + "{\"id\":\"a\",\"tool\":\"pr-diff\",\"args\":[\"Example.java\"]},"
                + "{\"id\":\"b\",\"tool\":\"review-upsert-finding\",\"arguments\":"
                + findingArguments("auth", "MEDIUM") + "}]}";
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text(mixed),
                        ChatTurn.text(legacyRunTools("r1", ReviewSubmissionToolExecutor.UPSERT_FINDING,
                                findingArguments("auth", "MEDIUM"))),
                        ChatTurn.text(legacyRunTools("r2", ReviewSubmissionToolExecutor.SET_ASSESSMENT,
                                assessmentArguments(1))),
                        ChatTurn.text(legacyRunTools("r3", ReviewSubmissionToolExecutor.COMPLETE,
                                completionArguments(2, 1))));

        assertThat(review()).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        // The refused turn applied nothing: the review holds exactly the finding of the later turn.
        assertThat(draft().findings()).hasSize(1);
    }

    // ------------------------------------------------------------------ learned guidance

    private static final AgentReviewLearningSettings CURATED =
            new AgentReviewLearningSettings(false, AgentReviewLearningMode.CURATED, Set.of("alex"));
    private static final KnowledgeLessonService.ActiveLesson LESSON =
            ActiveLessons.lesson(17, ActiveLessons.TEXT, List.of(), "authorization");

    /** Wires real lesson selection over a stubbed lesson store, as the factory does. */
    private void enableLessons() {
        lessons = new AgentReviewLessons(new LearningContextProvider(lessonService, usageRepository,
                outputRepository, new org.remus.giteabot.config.LearningProperties()),
                () -> currentLearning);
        service = serviceFor(platform, RepositoryType.GITEA);
    }

    private AgentReviewService.ReviewResult review(AgentReviewLearningSettings settings) {
        return service.reviewPullRequest(payload, 12, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false, settings);
    }

    private static long occurrences(String text, String needle) {
        long count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** Everything one request sends besides the system prompt: history plus the user message. */
    private static String requestText(List<AiMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        history.forEach(message -> sb.append(message.getContent()).append('\n'));
        return sb.append(userMessage).toString();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCuratedLessonReachesTheKickoffOnceInNativeAndLegacyTransports(boolean legacy) {
        enableLessons();
        when(lessonService.activeLessons(any(), anyInt())).thenReturn(List.of(LESSON));
        when(aiClient.supportsNativeTools()).thenReturn(!legacy);
        List<ChatTurn> turns = legacy
                ? List.of(ChatTurn.text(legacyRunTools("r1", ReviewSubmissionToolExecutor.UPSERT_FINDING,
                                findingArguments("auth", "MEDIUM"))),
                        ChatTurn.text(legacyRunTools("r2", ReviewSubmissionToolExecutor.SET_ASSESSMENT,
                                assessmentArguments(1))),
                        ChatTurn.text(legacyRunTools("r3", ReviewSubmissionToolExecutor.COMPLETE,
                                completionArguments(2, 1))))
                : List.of(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));
        List<String> requests = new ArrayList<>();
        List<String> systemPrompts = new ArrayList<>();
        List<String> kickoff = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    if (requests.isEmpty()) {
                        // Only the kickoff carries it, not a separate history message.
                        assertThat(invocation.<List<AiMessage>>getArgument(0)).noneSatisfy(message ->
                                assertThat(String.valueOf(message.getContent()))
                                        .contains("Repository guidance"));
                        kickoff.add(invocation.getArgument(1));
                    }
                    requests.add(requestText(invocation.getArgument(0), invocation.getArgument(1)));
                    systemPrompts.add(invocation.getArgument(3));
                    return turns.get(requests.size() - 1);
                });

        assertThat(review(CURATED)).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        String block = "Repository guidance: L17@1 · applies to the whole repository\n> "
                + ActiveLessons.TEXT + "\nEvidence: feedback FB31 on O1200-F2 (PR #42).";
        // The same block in both transports, delivered by the kickoff user message (after the task)
        // and exactly once per request; the operator system prompt never carries it.
        assertThat(kickoff).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Please review the following pull request.").endsWith(block));
        assertThat(requests).hasSize(3).allSatisfy(request -> {
            assertThat(occurrences(request, block)).isEqualTo(1);
            assertThat(occurrences(request, "## Repository guidance")).isEqualTo(1);
        });
        assertThat(systemPrompts).allSatisfy(prompt -> assertThat(prompt)
                .doesNotContain("Repository guidance").doesNotContain(ActiveLessons.TEXT));
        verify(usageRepository).save(argThat((LessonUsage usage) -> usage.getRevisionId() == 170L
                && usage.getLessonId() == 17L && Long.valueOf(1L).equals(usage.getOutputId())
                && usage.getPromptBlockHash() != null && usage.getPromptBlockHash().length() == 64
                && usage.getInvocationId() != null));
    }

    @Test
    void withLearningOffNoLessonIsReadOrAddedAndNoExtraModelCallHappens() {
        enableLessons();
        List<String> requests = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    requests.add(requestText(invocation.getArgument(0), invocation.getArgument(1)));
                    return List.of(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1))
                            .get(requests.size() - 1);
                });

        assertThat(review(new AgentReviewLearningSettings(true))).isEqualTo(
                AgentReviewService.ReviewResult.POSTED);

        assertThat(requests).hasSize(3).noneSatisfy(request -> assertThat(request)
                .contains("Repository guidance"));
        verifyNoInteractions(lessonService, usageRepository);
        verify(snapshotRepository).save(argThat(
                (org.remus.giteabot.review.structured.WorkflowOutputSnapshot snapshot) ->
                        "LEARNING_OFF".equals(snapshot.getLessonSelection())
                                && snapshot.getLessonRevisions() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lesson-disabled", "learning-off"})
    void guidanceRevokedDuringTheRunCancelsItBeforePublication(String revocation) {
        enableLessons();
        AtomicBoolean revoked = new AtomicBoolean();
        when(lessonService.activeLessons(any(), anyInt())).thenAnswer(invocation ->
                revoked.get() && revocation.equals("lesson-disabled") ? List.of() : List.of(LESSON));
        List<ChatTurn> turns = List.of(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));
        AtomicBoolean secondCall = new AtomicBoolean();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    if (secondCall.getAndSet(true)) {
                        // A maintainer acts while the model is thinking about round 2.
                        revoked.set(true);
                        if (revocation.equals("learning-off")) {
                            currentLearning = AgentReviewLearningSettings.DISABLED;
                        }
                        return turns.get(1);
                    }
                    return turns.getFirst();
                });

        assertThatThrownBy(() -> review(CURATED))
                .isInstanceOf(WorkflowCancelledException.class)
                .hasMessageContaining("L17@1");

        // Round 2's calls never ran: the draft keeps round 1's finding and is closed as cancelled.
        assertThat(draft().state()).isEqualTo(ReviewState.CANCELLED);
        assertThat(draft().assessmentCurrent()).isFalse();
        verify(aiClient, times(2)).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        assertNothingPublished();
    }

    @Test
    void guidanceRevokedAfterCompletionSuppressesThePublication() {
        enableLessons();
        // Selection and the three round boundaries see the lesson; the pre-publication check does not.
        when(lessonService.activeLessons(any(), anyInt()))
                .thenReturn(List.of(LESSON), List.of(LESSON), List.of(LESSON), List.of(LESSON), List.of());
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThatThrownBy(() -> review(CURATED))
                .isInstanceOf(WorkflowCancelledException.class)
                .hasMessageContaining("was not published")
                .hasMessageContaining("L17@1 is no longer active");

        assertThat(draft().state()).isEqualTo(ReviewState.FINALIZED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        assertThat(publications.values()).singleElement().satisfies(publication ->
                assertThat(publication.getState()).isEqualTo(ReviewPublicationState.SUPPRESSED));
    }

    @Test
    void unavailableRetrievalRunsTheReviewWithoutGuidanceAndRecordsThat() {
        enableLessons();
        when(lessonService.activeLessons(any(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        List<String> requests = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    requests.add(requestText(invocation.getArgument(0), invocation.getArgument(1)));
                    return List.of(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1))
                            .get(requests.size() - 1);
                });

        assertThat(review(new AgentReviewLearningSettings(true, AgentReviewLearningMode.CURATED,
                Set.of()))).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        assertThat(requests).noneSatisfy(request -> assertThat(request).contains("Repository guidance"));
        verify(usageRepository, never()).save(any());
        verify(snapshotRepository).save(argThat(
                (org.remus.giteabot.review.structured.WorkflowOutputSnapshot snapshot) ->
                        "UNAVAILABLE".equals(snapshot.getLessonSelection())
                                && snapshot.getLessonRevisions() == null
                                && snapshot.getLessonContextHash() == null));
    }

    @Test
    void theSnapshotNamesTheExactRevisionsAndBlockSupplied() {
        enableLessons();
        when(lessonService.activeLessons(any(), anyInt())).thenReturn(List.of(LESSON));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(findingTurn("auth"), assessmentTurn(1), completionTurn(2, 1));

        assertThat(review(new AgentReviewLearningSettings(true, AgentReviewLearningMode.CURATED,
                Set.of()))).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(snapshotRepository).save(argThat(
                (org.remus.giteabot.review.structured.WorkflowOutputSnapshot snapshot) ->
                        "SELECTED".equals(snapshot.getLessonSelection())
                                && "L17@1".equals(snapshot.getLessonRevisions())
                                && snapshot.getLessonContextHash() != null
                                && snapshot.getLessonContextHash().length() == 64));
    }

    @Test
    void aFollowUpSelectsCurrentGuidanceAfreshWithoutCountingUsage() {
        enableLessons();
        when(lessonService.activeLessons(any(), anyInt())).thenReturn(List.of(LESSON));
        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        when(aiClient.chatWithTools(anyList(), prompts.capture(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("The filter covers it."));

        assertThat(service.answerClarification(payload, "Is this endpoint protected?", 5, false, CURATED))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        assertThat(prompts.getValue()).contains("Is this endpoint protected?")
                .contains("Repository guidance: L17@1");
        assertThat(occurrences(prompts.getValue(), ActiveLessons.TEXT)).isEqualTo(1);
        verify(usageRepository, never()).save(any());
    }

    @Test
    void aFollowUpWithLearningOffGetsNoGuidance() {
        enableLessons();
        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        when(aiClient.chatWithTools(anyList(), prompts.capture(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("The filter covers it."));

        assertThat(service.answerClarification(payload, "Why?", 5, false,
                AgentReviewLearningSettings.DISABLED)).isEqualTo(AgentReviewService.ReviewResult.POSTED);

        assertThat(prompts.getValue()).doesNotContain("Repository guidance");
        verifyNoInteractions(lessonService);
    }

    // ------------------------------------------------------------------ helpers

    private AgentReviewService.ReviewResult review() {
        return service.reviewPullRequest(payload, 12, false,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false, AgentReviewLearningSettings.DISABLED);
    }

    private DraftSnapshot draft() {
        return reviewService.findDraft(1L).orElseThrow();
    }

    private void assertNothingPublished() {
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(eventHooks);
        verify(workspace).close();
    }

    private static ChatTurn findingTurn(String clientKey) {
        return findingTurn(clientKey, "MEDIUM");
    }

    private static ChatTurn findingTurn(String clientKey, String severity) {
        return toolTurn("call-" + clientKey, ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArguments(clientKey, severity));
    }

    private static ObjectNode findingArguments(String clientKey, String severity) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "finding-" + clientKey);
        arguments.put("clientKey", clientKey);
        arguments.put("severity", severity);
        arguments.put("category", "authorization");
        arguments.put("title", "Settings update may bypass authorization");
        arguments.put("explanation", "The observed update path does not show the permission check.");
        arguments.put("impact", "An unauthorized caller could change settings.");
        arguments.put("recommendation", "Verify the enclosing filter.");
        ObjectNode evidence = arguments.putArray("evidence").addObject();
        evidence.put("path", "src/SettingsController.java");
        evidence.put("side", "HEAD");
        evidence.put("startLine", 42);
        evidence.put("endLine", 49);
        evidence.put("description", "Settings update entry point.");
        return arguments;
    }

    private static String legacyRunTools(String id, String tool, ObjectNode arguments) {
        return "{\"runTools\":[{\"id\":\"" + id + "\",\"tool\":\"" + tool
                + "\",\"arguments\":" + arguments + "}]}";
    }

    private static ObjectNode assessmentArguments(int draftRevision) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "assessment-1");
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("summary", "One authorization concern needs attention.");
        arguments.put("conclusion", "FINDINGS_PRESENT");
        arguments.put("reviewedAreas", "settings controller and security configuration");
        arguments.putNull("limitations");
        return arguments;
    }

    private static ObjectNode completionArguments(int draftRevision, int findingCount) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "complete-1");
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("expectedFindingCount", findingCount);
        return arguments;
    }

    private static ChatTurn assessmentTurn(int draftRevision) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "assessment-1");
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("summary", "One authorization concern needs attention.");
        arguments.put("conclusion", "FINDINGS_PRESENT");
        arguments.put("reviewedAreas", "settings controller and security configuration");
        arguments.putNull("limitations");
        return toolTurn("call-assessment", ReviewSubmissionToolExecutor.SET_ASSESSMENT, arguments);
    }

    private static ChatTurn completionTurn(int draftRevision, int findingCount) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "complete-1");
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("expectedFindingCount", findingCount);
        return toolTurn("call-complete", ReviewSubmissionToolExecutor.COMPLETE, arguments);
    }

    private static ChatTurn mixedCompletionTurn() {
        ObjectNode finding = findingArguments("late", "MEDIUM");
        ObjectNode complete = JSON.createObjectNode();
        complete.put("operationId", "complete-early");
        complete.put("expectedDraftRevision", 0);
        complete.put("expectedFindingCount", 1);
        return new ChatTurn("", List.of(
                new ToolCall("call-late", ReviewSubmissionToolExecutor.UPSERT_FINDING, finding),
                new ToolCall("call-complete", ReviewSubmissionToolExecutor.COMPLETE, complete)),
                StopReason.TOOL_USE, 10, 5);
    }

    private static ChatTurn toolTurn(String id, String tool, ObjectNode arguments) {
        return new ChatTurn("", List.of(new ToolCall(id, tool, arguments)), StopReason.TOOL_USE, 10, 5);
    }

    private static ChatTurn exploration(int round) {
        return new ChatTurn("Let me check the remaining files...",
                List.of(new ToolCall("read-diff-" + round, "pr-diff",
                        AgentJackson.mapper().createObjectNode().put("path", "Example.java"))),
                StopReason.TOOL_USE, 100, 20);
    }
}
