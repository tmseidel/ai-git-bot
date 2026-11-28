package org.remus.giteabot.reviewui;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.learning.feedback.FeedbackEventType;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackTarget;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackEvent;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackEventRepository;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackRepository;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonRepository;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LearningEvent;
import org.remus.giteabot.learning.knowledge.LearningEventRepository;
import org.remus.giteabot.learning.knowledge.LessonEventType;
import org.remus.giteabot.learning.knowledge.LessonState;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningMode;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.review.structured.AssessmentDraft;
import org.remus.giteabot.review.structured.FindingDraft;
import org.remus.giteabot.review.structured.LocationDraft;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewDraftRequest;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewSide;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
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
 * The Reviews pages over records written through the production services (H2): every page renders
 * them, and every action changes state exactly as its Git command would — as {@code admin:<name>},
 * with the learning-mode gates, without a pending pull-request confirmation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewUiIntegrationTest {

    private static final long INTEGRATION = 31L;
    private static final long BOT = 32L;
    private static final String OWNER = "acme";
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final AtomicLong COMMENTS = new AtomicLong(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private StructuredReviewService reviewService;
    @Autowired private FeedbackService feedbackService;
    @Autowired private KnowledgeLessonService lessonService;
    @Autowired private KnowledgeLessonRepository lessonRepository;
    @Autowired private LearningEventRepository learningEventRepository;
    @Autowired private WorkflowFeedbackRepository feedbackRepository;
    @Autowired private WorkflowFeedbackEventRepository feedbackEventRepository;
    @Autowired private ReviewUiQueryService queryService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockitoBean private BotRepository botRepository;
    @MockitoBean private AgentReviewLearningSettingsResolver learningSettingsResolver;

    private String repo;
    private String review;
    private String feedback;
    private AgentReviewLearningSettings settings = curated();

    @BeforeEach
    void setUp() {
        Bot bot = new Bot();
        bot.setId(BOT);
        bot.setName("review-bot");
        when(botRepository.findById(BOT)).thenReturn(Optional.of(bot));
        when(botRepository.findAllById(anyIterable())).thenReturn(List.of(bot));
        when(learningSettingsResolver.resolve(any(Bot.class))).thenAnswer(invocation -> settings);

        repo = "ui-" + UUID.randomUUID();
        review = finalizedReview(42L);
        feedback = feedbackService.submit(scope(42L), comment(42L), FeedbackTarget.finding(review, 1),
                FeedbackVerdict.INCORRECT, "The permission check is in the security filter.", "alex")
                .reference();
    }

    @Test
    void everyPageRendersTheStoredRecords() throws Exception {
        String project = OWNER + "/" + repo;
        mockMvc.perform(get("/reviews").param("project", project).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(review)))
                .andExpect(content().string(containsString("review-bot")));
        mockMvc.perform(get("/reviews/" + review).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Missing permission check")))
                .andExpect(content().string(containsString("class=\"md text-pre mb-2 mt-2 small\">The `check` is **missing**.<")))
                .andExpect(content().string(containsString("purify.min.js")))
                .andExpect(content().string(containsString(feedback)))
                .andExpect(content().string(containsString("src/Foo.java")));
        mockMvc.perform(get("/reviews/feedback").param("project", project).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(feedback)))
                .andExpect(content().string(containsString("The permission check is in the security filter.")));

        String lesson = propose("Inspect the security filter chain first.", "src/**");
        mockMvc.perform(get("/reviews/lessons").param("project", project).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(lesson)));
        mockMvc.perform(get("/reviews/lessons/" + lesson).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Inspect the security filter chain first.")))
                .andExpect(content().string(containsString("admin:alice")));
        mockMvc.perform(get("/reviews/O99999999").with(user("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void theNavigationAndPagesAreTranslated() throws Exception {
        mockMvc.perform(get("/reviews/lessons").param("lang", "de").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lektionen")))
                .andExpect(content().string(containsString("Handlungsbedarf")));
    }

    @Test
    void proposingAndActivatingRunThroughTheLessonServiceAsTheAdmin() throws Exception {
        String lesson = propose("Inspect the security filter chain first.", "src/**");
        KnowledgeLessonService.Summary candidate = summary(lesson);
        assertThat(candidate.state()).isEqualTo(LessonState.CANDIDATE);
        assertThat(candidate.current().paths()).containsExactly("src/**");
        assertThat(candidate.current().createdBy()).isEqualTo("admin:alice");

        String requestId = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/reviews/lessons/" + lesson + "/activate").param("revision", "1")
                            .param("requestId", requestId).with(csrf()).with(user("alice")))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reviews/lessons/" + lesson))
                    .andExpect(flash().attribute("success", containsString(lesson + "@1 is active")));
        }

        assertThat(summary(lesson).state()).isEqualTo(LessonState.ACTIVE);
        List<LearningEvent> events = events(lesson);
        assertThat(events).filteredOn(event -> event.getEventType() == LessonEventType.ACTIVATED)
                .as("a double submit is one activation").hasSize(1);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getActor()).isEqualTo("admin:alice");
            assertThat(event.getAcknowledgement()).as("nothing to confirm on a pull request").isNull();
        });
    }

    @Test
    void proposingRevisingAndActivatingNeedCuratedLearningButRejectingAndDisablingDoNot() throws Exception {
        String lesson = propose("Inspect the security filter chain first.", "");
        mockMvc.perform(post("/reviews/lessons/" + lesson + "/activate").param("revision", "1")
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(flash().attributeExists("success"));

        settings = new AgentReviewLearningSettings(true, AgentReviewLearningMode.OFF, Set.of());
        mockMvc.perform(post("/reviews/feedback/" + feedback + "/propose").param("text", "Another lesson.")
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(flash().attribute("error", containsString("Curated learning is off for this bot")));
        mockMvc.perform(post("/reviews/lessons/" + lesson + "/revise").param("text", "Changed wording.")
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(flash().attribute("error", containsString("learning mode OFF")));
        assertThat(lessonRepository.findAll()).filteredOn(l -> repo.equals(l.getRepoName())).hasSize(1);
        assertThat(summary(lesson).latestRevision()).isEqualTo(1);

        mockMvc.perform(post("/reviews/lessons/" + lesson + "/disable").param("reason", "Too broad.")
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(flash().attribute("success", containsString(lesson + " was disabled")));
        assertThat(summary(lesson).state()).isEqualTo(LessonState.DISABLED);
    }

    @Test
    void aRetractionFromThePanelFlagsCitingLessonsAndLeavesNoPendingConfirmation() throws Exception {
        String lesson = propose("Inspect the security filter chain first.", "");

        mockMvc.perform(post("/reviews/feedback/" + feedback + "/retract")
                        .param("returnTo", "/reviews/feedback?project=" + OWNER + "/" + repo)
                        .with(csrf()).with(user("alice")))
                .andExpect(redirectedUrl("/reviews/feedback?project=" + OWNER + "/" + repo))
                .andExpect(flash().attribute("success", containsString(feedback + " was retracted")));

        Long feedbackId = feedbackRepository.findByReference(feedback).orElseThrow().getId();
        assertThat(feedbackRepository.findById(feedbackId).orElseThrow().getState())
                .isEqualTo(FeedbackState.RETRACTED);
        WorkflowFeedbackEvent retracted = feedbackEventRepository.findByFeedbackIdOrderByIdAsc(feedbackId)
                .stream().filter(event -> event.getEventType() == FeedbackEventType.RETRACTED)
                .findFirst().orElseThrow();
        assertThat(retracted.getActor()).isEqualTo("admin:alice");
        assertThat(retracted.getAcknowledgement()).isNull();
        assertThat(summary(lesson).reviewRequired()).isTrue();
    }

    @Test
    void serviceRefusalsAreShownAsMessagesAndChangeNothing() throws Exception {
        String lesson = propose("Inspect the security filter chain first.", "");
        mockMvc.perform(post("/reviews/lessons/" + lesson + "/activate").param("revision", "5")
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("Nothing was changed")));
        mockMvc.perform(post("/reviews/feedback/" + feedback + "/propose").param("text", "x")
                        .param("paths", "../outside").param("requestId", UUID.randomUUID().toString())
                        .with(csrf()).with(user("alice")))
                .andExpect(flash().attribute("error", containsString("Nothing was changed")));
        assertThat(summary(lesson).state()).isEqualTo(LessonState.CANDIDATE);
    }

    @Test
    void actionsNeedCsrfAndNeverRedirectOffTheReviewsPages() throws Exception {
        mockMvc.perform(post("/reviews/feedback/" + feedback + "/retract").with(user("alice")))
                .andExpect(status().isForbidden());
        assertThat(feedbackRepository.findByReference(feedback).orElseThrow().getState())
                .isEqualTo(FeedbackState.EFFECTIVE);

        mockMvc.perform(post("/reviews/feedback/" + feedback + "/retract").param("returnTo", "//evil.example")
                        .with(csrf()).with(user("alice")))
                .andExpect(redirectedUrl("/reviews/feedback"));
    }

    @Test
    void needsAttentionListsCandidatesAndLessonsKeptOutOfReviewsOnly() throws Exception {
        String candidate = propose("Inspect the security filter chain first.", "");
        String second = finalizedReview(43L);
        String otherFeedback = feedbackService.submit(scope(43L), comment(43L), FeedbackTarget.finding(second, 1),
                FeedbackVerdict.INCORRECT, "Covered by the gateway.", "alex").reference();
        String active = propose(otherFeedback, "Webhooks are verified in the gateway.", "");
        mockMvc.perform(post("/reviews/lessons/" + active + "/activate").param("revision", "1")
                .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")));

        ReviewUiFilterForm filter = new ReviewUiFilterForm();
        filter.setProject(OWNER + "/" + repo);
        assertThat(queryService.lessons(filter).page().rows()).as("the tab opens on every lesson")
                .extracting(row -> row.summary().reference()).containsExactly(active, candidate);
        filter.setLessonState("attention");
        assertThat(queryService.lessons(filter).page().rows()).extracting(row -> row.summary().reference())
                .containsExactly(candidate);
        filter.setLessonState("ACTIVE");
        assertThat(queryService.lessons(filter).page().rows()).extracting(row -> row.summary().reference())
                .containsExactly(active);
        filter.setLessonState("all");
        assertThat(queryService.lessons(filter).page().total()).isEqualTo(2);
    }

    @Test
    void listingsTakeAFixedNumberOfQueriesWhateverTheNumberOfRows() throws Exception {
        ReviewUiFilterForm filter = new ReviewUiFilterForm();
        filter.setProject(OWNER + "/" + repo);
        long reviewsWithOne = statements(() -> queryService.reviews(filter));
        long feedbackWithOne = statements(() -> queryService.feedback(filter));
        for (long pr = 50; pr < 54; pr++) {
            String more = finalizedReview(pr);
            feedbackService.submit(scope(pr), comment(pr), FeedbackTarget.finding(more, 1),
                    FeedbackVerdict.USEFUL, "Right.", "alex");
        }
        assertThat(queryService.reviews(filter).rows()).hasSize(5);
        assertThat(statements(() -> queryService.reviews(filter))).as("reviews").isEqualTo(reviewsWithOne);
        assertThat(statements(() -> queryService.feedback(filter))).as("feedback").isEqualTo(feedbackWithOne);
    }

    @Test
    void tablesPageWithTheRowsPerPageChosenLikeOnTheWorkflowRunsPage() throws Exception {
        for (long pr = 60; pr < 64; pr++) {
            finalizedReview(pr);
        }
        String project = OWNER + "/" + repo;
        ReviewUiFilterForm filter = new ReviewUiFilterForm();
        filter.setProject(project);
        filter.setSize(2);
        filter.setPage(3);
        ReviewUiViews.PageOf<ReviewUiViews.ReviewRow> last = queryService.reviews(filter);
        assertThat(last.total()).isEqualTo(5);
        assertThat(last.pages()).isEqualTo(3);
        assertThat(last.rows()).extracting(ReviewUiViews.ReviewRow::reference).containsExactly(review);

        mockMvc.perform(get("/reviews").param("project", project).param("size", "2").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("page=3&amp;size=2")))
                .andExpect(content().string(containsString("<option value=\"20\">20</option>")))
                .andExpect(content().string(containsString("name=\"project\" value=\"" + project + "\"")));
        mockMvc.perform(get("/reviews").param("project", project).with(user("alice")))
                .andExpect(content().string(containsString("Rows per page")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("class=\"pagination"))));
    }

    // ------------------------------------------------------------------ helpers

    private String propose(String text, String paths) throws Exception {
        return propose(feedback, text, paths);
    }

    private String propose(String feedbackReference, String text, String paths) throws Exception {
        MvcResult result = mockMvc.perform(post("/reviews/feedback/" + feedbackReference + "/propose")
                        .param("text", text).param("paths", paths)
                        .param("requestId", UUID.randomUUID().toString()).with(csrf()).with(user("alice")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"))
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/reviews/lessons/L");
        return location.substring("/reviews/lessons/".length());
    }

    private KnowledgeLessonService.Summary summary(String lesson) {
        return lessonService.show(new FeedbackScope(INTEGRATION, BOT, OWNER, repo, null), lesson)
                .orElseThrow().summary();
    }

    private List<LearningEvent> events(String lesson) {
        return learningEventRepository.findByLessonIdOrderByIdAsc(
                lessonRepository.findByReference(lesson).orElseThrow().getId());
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

    private static AgentReviewLearningSettings curated() {
        return new AgentReviewLearningSettings(true, AgentReviewLearningMode.CURATED, Set.of());
    }

    private FeedbackScope scope(long pr) {
        return new FeedbackScope(INTEGRATION, BOT, OWNER, repo, pr);
    }

    private FeedbackSource comment(long pr) {
        return new FeedbackSource(INTEGRATION, BOT, OWNER, repo, "repo-1", FeedbackSource.SubjectKind.PR, pr,
                "PR_COMMENT", "c" + COMMENTS.getAndIncrement());
    }

    private String finalizedReview(long pr) {
        var handle = reviewService.createDraft(new ReviewDraftRequest(INTEGRATION, BOT, OWNER, repo,
                null, pr, null, UUID.randomUUID().toString(), null, HEAD_SHA));
        reviewService.upsertFinding(handle.reviewId(), null, new FindingDraft("auth",
                ReviewSeverity.MEDIUM, "authorization", "Missing permission check", "The `check` is **missing**.",
                "Impact.", "Recommendation.", List.of(new LocationDraft("src/Foo.java",
                ReviewSide.HEAD, 1, 2, "Evidence."))));
        reviewService.setAssessment(handle.reviewId(), 1, new AssessmentDraft("Summary.",
                ReviewConclusion.FINDINGS_PRESENT, "Correctness.", null));
        reviewService.finalizeReview(handle.reviewId(), 2, 1);
        return reviewService.findDraft(handle.reviewId()).orElseThrow().outputReference();
    }
}
