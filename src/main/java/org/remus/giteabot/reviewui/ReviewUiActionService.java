package org.remus.giteabot.reviewui;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.learning.feedback.FeedbackRejectedException;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.WorkflowFeedback;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackRepository;
import org.remus.giteabot.learning.knowledge.KnowledgeLesson;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonRepository;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LessonPathScope;
import org.remus.giteabot.learning.knowledge.LessonRejectedException;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The write side of the Reviews pages: the curation actions of the {@code @bot feedback} and
 * {@code @bot learning} commands, run through the same services with the record's own scope.
 *
 * <p>The actor is the signed-in admin, recorded as {@code admin:<username>}. Admins can edit every
 * bot's learning-maintainer list, so that list is not consulted; the learning-mode gates are:
 * proposing, revising and activating need {@code CURATED}, rejecting, disabling and retracting work
 * in every mode. Nothing is posted to a pull request — the result is shown in the panel.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewUiActionService {

    static final String ACTOR_PREFIX = "admin:";
    private static final int MAX_ACTOR_CHARS = 200;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final KnowledgeLessonService lessonService;
    private final FeedbackService feedbackService;
    private final KnowledgeLessonRepository lessonRepository;
    private final WorkflowFeedbackRepository feedbackRepository;
    private final BotRepository botRepository;
    private final AgentReviewLearningSettingsResolver learningSettingsResolver;

    /**
     * What happened, as a message key with arguments. A refusal carries the service's own
     * explanation ({@code reviews.action.refused}).
     */
    public record Result(boolean success, String messageKey, Object... args) {

        static Result done(String key, Object... args) {
            return new Result(true, key, args);
        }

        static Result refused(String key, Object... args) {
            return new Result(false, key, args);
        }
    }

    public Result retract(String feedbackReference, String admin) {
        return withFeedback(feedbackReference, record -> {
            FeedbackService.Recorded recorded = feedbackService.retractFromAdminPanel(scopeOf(record),
                    record.getReference(), actorOf(admin));
            return Result.done("reviews.action.retracted", recorded.reference());
        });
    }

    public Result propose(String feedbackReference, String text, String paths, String requestId,
                          String admin) {
        return withFeedback(feedbackReference, record -> {
            Optional<Result> gate = curationGate(record.getBotId());
            if (gate.isPresent()) {
                return gate.get();
            }
            FeedbackSource source = FeedbackSource.adminUi(record.getGitIntegrationId(),
                    record.getBotId(), record.getRepoOwner(), record.getRepoName(),
                    record.getProviderRepositoryId(), requestIdOf(requestId));
            KnowledgeLessonService.Recorded recorded = lessonService.propose(scopeOf(record), source,
                    record.getReference(), pathsOf(paths), text, actorOf(admin));
            return Result.done("reviews.action.proposed", recorded.revisionReference());
        });
    }

    public Result revise(String lessonReference, String text, String paths, String requestId,
                         String admin) {
        return withLesson(lessonReference, lesson -> {
            Optional<Result> gate = curationGate(lesson.getBotId());
            if (gate.isPresent()) {
                return gate.get();
            }
            KnowledgeLessonService.Recorded recorded = lessonService.revise(scopeOf(lesson),
                    sourceOf(lesson, requestId), lesson.getReference(), pathsOf(paths), text,
                    actorOf(admin));
            return Result.done("reviews.action.revised", recorded.revisionReference());
        });
    }

    public Result activate(String lessonReference, int revision, String requestId, String admin) {
        return withLesson(lessonReference, lesson -> {
            Optional<Result> gate = curationGate(lesson.getBotId());
            if (gate.isPresent()) {
                return gate.get();
            }
            KnowledgeLessonService.Recorded recorded = lessonService.activate(scopeOf(lesson),
                    sourceOf(lesson, requestId), lesson.getReference(), revision, actorOf(admin));
            return Result.done("reviews.action.activated", recorded.revisionReference());
        });
    }

    public Result reject(String lessonReference, int revision, String requestId, String admin) {
        return withLesson(lessonReference, lesson -> {
            KnowledgeLessonService.Recorded recorded = lessonService.reject(scopeOf(lesson),
                    sourceOf(lesson, requestId), lesson.getReference(), revision, actorOf(admin));
            return Result.done("reviews.action.rejected", recorded.revisionReference());
        });
    }

    public Result disable(String lessonReference, String reason, String requestId, String admin) {
        return withLesson(lessonReference, lesson -> {
            KnowledgeLessonService.Recorded recorded = lessonService.disable(scopeOf(lesson),
                    sourceOf(lesson, requestId), lesson.getReference(), reason, actorOf(admin));
            return Result.done("reviews.action.disabled", recorded.lessonReference());
        });
    }

    // ------------------------------------------------------------------ helpers

    private Result withFeedback(String reference, Function<WorkflowFeedback, Result> action) {
        Optional<WorkflowFeedback> record = reference == null ? Optional.empty()
                : feedbackRepository.findByReference(reference.trim());
        return record.map(found -> guarded(reference, () -> action.apply(found)))
                .orElseGet(() -> Result.refused("reviews.action.notFound", reference));
    }

    private Result withLesson(String reference, Function<KnowledgeLesson, Result> action) {
        Optional<KnowledgeLesson> lesson = reference == null ? Optional.empty()
                : lessonRepository.findByReference(reference.trim())
                        .filter(found -> StructuredReviewService.WORKFLOW_KEY.equals(found.getWorkflowKey()));
        return lesson.map(found -> guarded(reference, () -> action.apply(found)))
                .orElseGet(() -> Result.refused("reviews.action.notFound", reference));
    }

    private static Result guarded(String reference, java.util.function.Supplier<Result> action) {
        try {
            return action.get();
        } catch (LessonRejectedException | FeedbackRejectedException refused) {
            return Result.refused("reviews.action.refused", refused.getMessage());
        } catch (RuntimeException e) {
            // Details stay in the log: an exception message can carry SQL (CWE-209).
            log.error("Admin-panel action on {} failed: {}", reference, e.getMessage(), e);
            return Result.refused("reviews.action.failed", reference);
        }
    }

    /** Refusal when the bot's agentic review does not run curated learning right now. */
    private Optional<Result> curationGate(Long botId) {
        AgentReviewLearningSettings settings = botId == null ? AgentReviewLearningSettings.DISABLED
                : botRepository.findById(botId).map(learningSettingsResolver::resolve)
                        .orElse(AgentReviewLearningSettings.DISABLED);
        return settings.curatedLearning() ? Optional.empty()
                : Optional.of(Result.refused("reviews.action.curatedOff", settings.learningMode().name()));
    }

    private static FeedbackScope scopeOf(WorkflowFeedback record) {
        return new FeedbackScope(record.getGitIntegrationId(), record.getBotId(), record.getRepoOwner(),
                record.getRepoName(), record.getPrNumber());
    }

    private static FeedbackScope scopeOf(KnowledgeLesson lesson) {
        return new FeedbackScope(lesson.getGitIntegrationId(), lesson.getBotId(), lesson.getRepoOwner(),
                lesson.getRepoName(), null);
    }

    private static FeedbackSource sourceOf(KnowledgeLesson lesson, String requestId) {
        return FeedbackSource.adminUi(lesson.getGitIntegrationId(), lesson.getBotId(),
                lesson.getRepoOwner(), lesson.getRepoName(), lesson.getProviderRepositoryId(),
                requestIdOf(requestId));
    }

    /** Blank means repository-wide; otherwise the same patterns a {@code paths:} line accepts. */
    static List<String> pathsOf(String paths) {
        return paths == null || paths.isBlank() ? List.of() : LessonPathScope.parse(paths);
    }

    /**
     * The form's idempotency key. A missing or malformed key cannot be told apart from a new action,
     * so it is replaced by a fresh one: the action still runs once, a double submit may run twice.
     */
    static String requestIdOf(String requestId) {
        return requestId != null && REQUEST_ID.matcher(requestId).matches()
                ? requestId : UUID.randomUUID().toString();
    }

    static String actorOf(String admin) {
        String name = admin == null || admin.isBlank() ? "unknown" : admin.trim();
        String actor = ACTOR_PREFIX + name;
        return actor.length() <= MAX_ACTOR_CHARS ? actor : actor.substring(0, MAX_ACTOR_CHARS);
    }
}
