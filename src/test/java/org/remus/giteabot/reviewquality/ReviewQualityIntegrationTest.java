package org.remus.giteabot.reviewquality;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationRepository;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.aiusage.AiInteraction;
import org.remus.giteabot.aiusage.AiInteractionRepository;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackTarget;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.AssessmentDraft;
import org.remus.giteabot.review.structured.FindingDraft;
import org.remus.giteabot.review.structured.LocationDraft;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewDraftHandle;
import org.remus.giteabot.review.structured.ReviewDraftRequest;
import org.remus.giteabot.review.structured.ReviewFinding;
import org.remus.giteabot.review.structured.ReviewRecordRepository;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewSide;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.remus.giteabot.review.structured.WorkflowOutputRepository;
import org.remus.giteabot.workflowrun.RunStatus;
import org.remus.giteabot.workflowrun.WorkflowRun;
import org.remus.giteabot.workflowrun.WorkflowRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Review quality page over records written through the production services (H2): reviews with
 * findings matched at finalization, feedback, pull-request outcomes and AI usage — and the counting
 * rules, pages, gates and the assumptions form on top of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "review-quality.min-sample=1")
class ReviewQualityIntegrationTest {

    private static final long INTEGRATION = 41L;
    private static final long BOT = 42L;
    private static final String OWNER = "acme";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final AtomicLong COMMENTS = new AtomicLong(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private StructuredReviewService reviewService;
    @Autowired private FeedbackService feedbackService;
    @Autowired private PrOutcomeService outcomeService;
    @Autowired private ReviewQualityService qualityService;
    @Autowired private ReviewQualitySettingsService settingsService;
    @Autowired private ReviewRecordRepository reviewRepository;
    @Autowired private WorkflowOutputRepository outputRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private AiInteractionRepository interactionRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TransactionTemplate transactions;

    @MockitoBean private BotRepository botRepository;
    @MockitoBean private AiIntegrationRepository aiIntegrationRepository;

    private Bot bot;
    private String repo;

    @BeforeEach
    void setUp() {
        GitIntegration integration = new GitIntegration();
        integration.setId(INTEGRATION);
        bot = new Bot();
        bot.setId(BOT);
        bot.setName("quality-bot");
        bot.setGitIntegration(integration);
        when(botRepository.findById(BOT)).thenReturn(Optional.of(bot));
        when(botRepository.findAllById(anyIterable())).thenReturn(List.of(bot));
        AiIntegration priced = new AiIntegration();
        priced.setName("priced-ai");
        priced.setPriceInputPerMtok(new BigDecimal("3"));
        priced.setPriceOutputPerMtok(new BigDecimal("15"));
        when(aiIntegrationRepository.findAll()).thenReturn(List.of(priced));
        repo = "rq-" + UUID.randomUUID();
    }

    @Test
    void findingsOfARepeatedReviewAreMatchedToThePreviousReviewWhenItIsFinalized() {
        long first = review(1, "alex", finding("auth", ReviewSeverity.BLOCKER, "security",
                        "Missing permission check in OrderController.cancel", "src/main/java/shop/OrderController.java", 88),
                finding("loop", ReviewSeverity.MEDIUM, "performance", "Query inside a loop", "src/main/java/shop/Orders.java", 10));
        long second = review(1, "alex", finding("auth2", ReviewSeverity.BLOCKER, "Auth",
                "OrderController.cancel still lacks a permission check", "src/main/java/shop/OrderController.java", 120));

        transactions.executeWithoutResult(status -> {
            assertThat(reviewRepository.findById(second).orElseThrow().isFingerprinted()).isTrue();
            ReviewFinding before = reviewRepository.findById(first).orElseThrow().getFindings().getFirst();
            ReviewFinding after = reviewRepository.findById(second).orElseThrow().getFindings().getFirst();
            assertThat(after.getPreviousFindingId()).isEqualTo(before.getId());
            assertThat(after.getOriginFindingId()).isEqualTo(before.getId());
            assertThat(before.getOriginFindingId()).isEqualTo(before.getId());
            assertThat(after.getFingerprint()).hasSize(64);
            assertThat(reviewRepository.findById(second).orElseThrow().getDiffAdditions()).isEqualTo(120);
            assertThat(reviewRepository.findById(second).orElseThrow().getOutput().getPrAuthor()).isEqualTo("alex");
        });
    }

    @Test
    void theCountingRulesHoldOverStoredRecords() {
        long first = review(1, "alex",
                finding("a", ReviewSeverity.BLOCKER, "security", "Missing permission check", "src/A.java", 10),
                finding("b", ReviewSeverity.MEDIUM, "performance", "Query inside a loop", "src/B.java", 20));
        review(1, "alex", finding("b2", ReviewSeverity.MEDIUM, "performance", "Query inside a loop", "src/B.java", 22),
                finding("c", ReviewSeverity.LOW, "style", "Unused import", "src/C.java", 1));
        long other = review(2, "kim", finding("d", ReviewSeverity.LOW, "testing", "No test for the empty cart", "src/D.java", 5),
                finding("e", ReviewSeverity.BLOCKER, "security", "Secret in log line", "src/E.java", 7));
        reviewService.closeDraft(reviewService.createDraft(request(3, "kim")).reviewId(), ReviewState.FAILED);
        withdrawLastFinding(other);

        ReviewQualityFilterForm filter = filter();
        List<ReviewQualityViews.Kpi> kpis = qualityService.overview(filter, Locale.ENGLISH).kpis();
        assertThat(kpi(kpis, "reviewQuality.kpi.prs").value()).as("PR 3 failed and does not count").isEqualTo("2");
        assertThat(kpi(kpis, "reviewQuality.kpi.findings").value())
                .as("first reviews only, withdrawn excluded: (2 + 1) / 2").isEqualTo("1.5");
        assertThat(kpi(kpis, "reviewQuality.kpi.blockers").value()).isEqualTo("0.50");
        assertThat(kpi(kpis, "reviewQuality.kpi.resolved").value()).as("blocker resolved, medium persisting")
                .isEqualTo("50%");

        ReviewQualityViews.PrRow row = qualityService.prs(filter, Locale.ENGLISH).rows().rows().getFirst();
        assertThat(row.number()).isEqualTo(1);
        assertThat(List.of(row.resolved(), row.persisting(), row.added())).containsExactly(1, 1, 1);
        assertThat(row.blockersLeft()).isZero();
        assertThat(first).isPositive();
    }

    @Test
    void mergedPullRequestsAndBlockersLeftAtMergeAreRecordedFromTheWebhook() {
        review(5, "alex", finding("a", ReviewSeverity.BLOCKER, "security", "Missing permission check", "src/A.java", 10));
        outcomeService.closed(bot, payload(5, true));
        review(6, "alex", finding("b", ReviewSeverity.LOW, "style", "Unused import", "src/B.java", 1));
        review(6, "alex");
        outcomeService.closed(bot, payload(6, false));
        outcomeService.reopened(bot, payload(6, false));

        ReviewQualityViews.PrsView view = qualityService.prs(filter(), Locale.ENGLISH);
        assertThat(kpi(view.kpis(), "reviewQuality.kpi.blockersAtMerge").value()).isEqualTo("100%");
        assertThat(view.rows().rows()).singleElement()
                .satisfies(row -> assertThat(row.outcomeKey()).isEqualTo("reviewQuality.outcome.open"));
    }

    @Test
    void costComesFromTheLinkedRunsPricedWithTheIntegrationsPrices() {
        long reviewId = review(7, "alex", finding("a", ReviewSeverity.BLOCKER, "security", "Missing check", "src/A.java", 1));
        reviewService.linkWorkflowRun(reviewId, run(7, 1_000_000, 100_000));
        run(7, 500_000, 0);

        ReviewQualityViews.CostView view = qualityService.cost(filter(), Locale.ENGLISH);
        assertThat(view.money()).isTrue();
        assertThat(view.reviewCost()).isEqualTo("$4.50");
        assertThat(view.followUpCost()).isEqualTo("$1.50");
        assertThat(kpi(view.kpis(), "reviewQuality.kpi.aiCost").value()).isEqualTo("$6.00");
    }

    @Test
    void everyTabRendersAndAuthorsStayOffUntilEnabled() throws Exception {
        long reviewId = review(8, "alex", finding("a", ReviewSeverity.MEDIUM, "security", "Missing check", "src/main/java/A.java", 1));
        String output = outputRepository.findAll().stream().filter(o -> repo.equals(o.getRepoName())).findFirst()
                .orElseThrow().getReference();
        feedbackService.submit(new FeedbackScope(INTEGRATION, BOT, OWNER, repo, 8L), comment(8),
                FeedbackTarget.finding(output, 1), FeedbackVerdict.INCORRECT, "Covered by the filter.", "kim");
        String project = OWNER + "/" + repo;
        for (String tab : List.of("", "/prs", "/repositories", "/topics", "/accuracy", "/cost")) {
            mockMvc.perform(get("/review-quality" + tab).param("project", project).with(user("admin")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("reviewQualityCharts")));
        }
        mockMvc.perform(get("/review-quality/repositories").param("project", project).with(user("admin")))
                .andExpect(content().string(containsString(project)));
        mockMvc.perform(get("/review-quality/accuracy").param("project", project).with(user("admin")))
                .andExpect(content().string(containsString("category=security&amp;uncited=true")));
        mockMvc.perform(get("/review-quality/authors").param("project", project).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REVIEW_QUALITY_AUTHOR_METRICS")))
                .andExpect(content().string(not(containsString("@alex"))));
        mockMvc.perform(get("/reviews/feedback").param("project", project).param("category", "security")
                        .with(user("admin")))
                .andExpect(content().string(containsString("Covered by the filter.")));
        mockMvc.perform(get("/reviews/feedback").param("project", project).param("category", "testing")
                        .with(user("admin")))
                .andExpect(content().string(not(containsString("Covered by the filter."))));
        assertThat(reviewId).isPositive();
    }

    @Test
    void theAssumptionsAreSavedWithCsrfAndRefusedOutOfRange() throws Exception {
        mockMvc.perform(post("/review-quality/assumptions").param("minutesBlocker", "60").param("minutesMedium", "20")
                        .param("minutesLow", "2").param("minutesIncorrect", "15").param("hourlyRate", "100")
                        .param("currency", "eur").param("returnQuery", "period=30d").with(user("admin")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/review-quality/assumptions").param("minutesBlocker", "60").param("minutesMedium", "20")
                        .param("minutesLow", "2").param("minutesIncorrect", "15").param("hourlyRate", "100")
                        .param("currency", "eur").param("returnQuery", "period=30d").with(csrf()).with(user("admin")))
                .andExpect(redirectedUrl("/review-quality/cost?period=30d"))
                .andExpect(flash().attributeExists("success"));
        ReviewQualitySettings saved = settingsService.current();
        assertThat(saved.getMinutesBlocker()).isEqualTo(60);
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.getUpdatedBy()).isEqualTo("admin:admin");

        mockMvc.perform(post("/review-quality/assumptions").param("minutesBlocker", "-1").param("minutesMedium", "20")
                        .param("minutesLow", "2").param("minutesIncorrect", "15").param("hourlyRate", "100")
                        .param("currency", "EUR").with(csrf()).with(user("admin")))
                .andExpect(flash().attributeExists("error"));
        assertThat(settingsService.current().getMinutesBlocker()).isEqualTo(60);
    }

    @Test
    void eachTabTakesAFixedNumberOfQueriesWhateverTheNumberOfReviews() {
        review(20, "alex", finding("a", ReviewSeverity.BLOCKER, "security", "Missing check", "src/A.java", 1));
        ReviewQualityFilterForm filter = filter();
        long overview = statements(() -> qualityService.overview(filter, Locale.ENGLISH));
        long prs = statements(() -> qualityService.prs(filter, Locale.ENGLISH));
        long cost = statements(() -> qualityService.cost(filter, Locale.ENGLISH));
        for (long pr = 21; pr < 26; pr++) {
            review(pr, "kim", finding("a", ReviewSeverity.MEDIUM, "testing", "No test", "src/T.java", 1));
            review(pr, "kim", finding("b", ReviewSeverity.LOW, "style", "Naming", "src/T.java", 9));
        }
        assertThat(statements(() -> qualityService.overview(filter, Locale.ENGLISH))).isEqualTo(overview);
        assertThat(statements(() -> qualityService.prs(filter, Locale.ENGLISH))).isEqualTo(prs);
        assertThat(statements(() -> qualityService.cost(filter, Locale.ENGLISH))).isEqualTo(cost);
    }

    // ------------------------------------------------------------------ fixtures

    private ReviewQualityFilterForm filter() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        filter.setProject(OWNER + "/" + repo);
        return filter;
    }

    private ReviewDraftRequest request(long pr, String author) {
        return new ReviewDraftRequest(INTEGRATION, BOT, OWNER, repo, null, pr, null, UUID.randomUUID().toString(),
                null, SHA, author, "PR " + pr, 3, 120, 30);
    }

    private static FindingDraft finding(String key, ReviewSeverity severity, String category, String title,
                                        String path, int line) {
        return new FindingDraft(key, severity, category, title, "Explanation.", "Impact.", "Recommendation.",
                List.of(new LocationDraft(path, ReviewSide.HEAD, line, line + 2, "Evidence.")));
    }

    private long review(long pr, String author, FindingDraft... findings) {
        ReviewDraftHandle handle = reviewService.createDraft(request(pr, author));
        for (FindingDraft finding : findings) {
            reviewService.upsertFinding(handle.reviewId(), null, finding);
        }
        int revision = reviewService.findDraft(handle.reviewId()).orElseThrow().draftRevision();
        reviewService.setAssessment(handle.reviewId(), revision, new AssessmentDraft("Summary.",
                findings.length == 0 ? ReviewConclusion.NO_FINDINGS : ReviewConclusion.FINDINGS_PRESENT,
                "Everything.", null));
        int finalRevision = reviewService.findDraft(handle.reviewId()).orElseThrow().draftRevision();
        reviewService.finalizeReview(handle.reviewId(), finalRevision, findings.length);
        return handle.reviewId();
    }

    private void withdrawLastFinding(long reviewId) {
        // Stands in for a finding the agent withdrew before finalizing; withdrawn findings never count.
        transactions.executeWithoutResult(status -> reviewRepository.findById(reviewId).orElseThrow()
                .getFindings().getLast().setWithdrawn(true));
    }

    private long run(long pr, long input, long output) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowType(StructuredReviewService.WORKFLOW_KEY);
        run.setBotId(BOT);
        run.setRepoOwner(OWNER);
        run.setRepoName(repo);
        run.setTriggerNumber(pr);
        run.setState(RunStatus.SUCCEEDED);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);
        AiInteraction interaction = new AiInteraction();
        interaction.setWorkflowRun(run);
        interaction.setRoundNumber(1);
        interaction.setAttempt(1);
        interaction.setAiIntegrationName("priced-ai");
        interaction.setStatus(RunStatus.SUCCEEDED);
        interaction.setStartedAt(Instant.now());
        interaction.setInputTokens(input);
        interaction.setOutputTokens(output);
        interactionRepository.save(interaction);
        return run.getId();
    }

    private WebhookPayload payload(long pr, boolean merged) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin(OWNER);
        repository.setOwner(owner);
        payload.setRepository(repository);
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(pr);
        pullRequest.setMerged(merged);
        payload.setPullRequest(pullRequest);
        return payload;
    }

    private FeedbackSource comment(long pr) {
        return new FeedbackSource(INTEGRATION, BOT, OWNER, repo, "repo-1", FeedbackSource.SubjectKind.PR, pr,
                "PR_COMMENT", "c" + COMMENTS.getAndIncrement());
    }

    private long statements(Runnable read) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            read.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static ReviewQualityViews.Kpi kpi(List<ReviewQualityViews.Kpi> kpis, String label) {
        return kpis.stream().filter(k -> k.labelKey().equals(label)).findFirst().orElseThrow();
    }
}
