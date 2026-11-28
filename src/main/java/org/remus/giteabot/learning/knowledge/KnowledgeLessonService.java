package org.remus.giteabot.learning.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackTargetType;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.feedback.FeedbackWithdrawnEvent;
import org.remus.giteabot.learning.feedback.LessonsCitingFeedback;
import org.remus.giteabot.learning.feedback.WorkflowFeedback;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackRepository;
import org.remus.giteabot.review.structured.ReferenceAllocator;
import org.remus.giteabot.review.structured.ReferenceNamespace;
import org.remus.giteabot.review.structured.ReviewFinding;
import org.remus.giteabot.review.structured.ReviewFindingLocation;
import org.remus.giteabot.review.structured.ReviewRecord;
import org.remus.giteabot.review.structured.ReviewRecordRepository;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Versioned, Git-managed lessons for agentic review.
 *
 * <p>Every change is a maintainer's explicit command — nothing here calls a model or activates on
 * its own. A lesson starts as a candidate written from one stored feedback record; activation names
 * an exact revision, so text that changed after a maintainer read it can never be approved by
 * accident; editing creates the next candidate revision instead of rewriting the active one.</p>
 *
 * <p>Scope is resolved before any text is read: a lesson belongs to one Git integration, bot,
 * repository and {@code agentic-review}, and a reference from anywhere else is indistinguishable
 * from one that does not exist. Concurrent transitions on one lesson are serialized by its optimistic
 * lock; the loser is told nothing changed. A webhook redelivery finds its own event through the
 * comment's source key and changes nothing.</p>
 *
 * <p>Authorization (who is a learning maintainer, whether curated learning is on) is the caller's
 * job, resolved from the bot's current configuration; this service enforces scope, lifecycle and
 * evidence rules.</p>
 */
@Slf4j
@Service
public class KnowledgeLessonService implements LessonsCitingFeedback {

    public static final int MAX_PAGE_SIZE = 20;
    public static final int MAX_LESSON_CHARS = 2_000;
    public static final String WORKFLOW_KEY = StructuredReviewService.WORKFLOW_KEY;

    private static final int MAX_DETAIL_CHARS = 1_000;
    private static final int MAX_REVIEW_REASON_CHARS = 500;

    private final KnowledgeLessonRepository lessonRepository;
    private final KnowledgeLessonRevisionRepository revisionRepository;
    private final KnowledgeLessonEvidenceRepository evidenceRepository;
    private final LearningEventRepository eventRepository;
    private final LessonUsageRepository usageRepository;
    private final WorkflowFeedbackRepository feedbackRepository;
    private final ReviewRecordRepository reviewRepository;
    private final ReferenceAllocator referenceAllocator;
    private final PlatformTransactionManager transactionManager;
    private final Duration recheckPeriod;

    public KnowledgeLessonService(KnowledgeLessonRepository lessonRepository,
                                  KnowledgeLessonRevisionRepository revisionRepository,
                                  KnowledgeLessonEvidenceRepository evidenceRepository,
                                  LearningEventRepository eventRepository,
                                  LessonUsageRepository usageRepository,
                                  WorkflowFeedbackRepository feedbackRepository,
                                  ReviewRecordRepository reviewRepository,
                                  ReferenceAllocator referenceAllocator,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${learning.lesson-recheck-days:180}") long recheckDays) {
        this.lessonRepository = lessonRepository;
        this.revisionRepository = revisionRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.usageRepository = usageRepository;
        this.feedbackRepository = feedbackRepository;
        this.reviewRepository = reviewRepository;
        this.referenceAllocator = referenceAllocator;
        this.transactionManager = transactionManager;
        this.recheckPeriod = Duration.ofDays(Math.max(1, recheckDays));
    }

    // ------------------------------------------------------------------ results

    /**
     * One accepted transition. {@code duplicate} means this comment was already handled — the
     * caller must not confirm it twice. {@code acknowledgementEventId} names the event whose
     * confirmation the caller tracks, with its state at the time of the call.
     */
    public record Recorded(String lessonReference, Integer revision, LessonEventType type,
                           boolean duplicate, Long acknowledgementEventId,
                           FeedbackAcknowledgement acknowledgement) {

        public String revisionReference() {
            return revision == null ? lessonReference
                    : LessonReference.revision(lessonReference, revision);
        }
    }

    /** One feedback record a revision cites, with its current state read from the source. */
    public record EvidenceView(String feedbackReference, String targetReference,
                               FeedbackTargetType targetType, FeedbackVerdict verdict,
                               FeedbackState state, String supersededBy, String actor,
                               Long prNumber, String commentKind, String commentId) {

        public boolean effective() {
            return state == FeedbackState.EFFECTIVE;
        }
    }

    public record RevisionView(int number, LessonState state, String text, List<String> paths,
                               String category, String sourceCommitSha, Instant recheckAfter,
                               String createdBy, Instant createdAt,
                               LessonActivationProvenance activationProvenance, String activatedBy,
                               Instant activatedAt, String endedBy, Instant endedAt,
                               List<EvidenceView> evidence, long usageCount) {

        public boolean repositoryWide() {
            return paths.isEmpty();
        }
    }

    public record HistoryEntry(LessonEventType type, Integer revision, String actor, String detail,
                               String relatedReference, Instant at) {
    }

    /**
     * Whether the guidance still stands on its evidence. {@code issues} describe why a maintainer
     * should look again; {@code conflicts} name other active lessons judging the same review target,
     * which must never be merged automatically.
     */
    public record Validation(List<String> issues, List<String> conflicts) {

        public boolean valid() {
            return issues.isEmpty() && conflicts.isEmpty();
        }

        /** Later automatic consolidation must leave conflicting or questioned lessons alone. */
        public boolean eligibleForAutomaticMerge() {
            return valid();
        }
    }

    /**
     * @param current         the active revision, or the latest one when none is active
     * @param pendingRevision a newer candidate waiting next to an active revision, or {@code null}
     */
    public record Summary(Long id, String reference, LessonState state, Integer activeRevision,
                          int latestRevision, RevisionView current, Integer pendingRevision,
                          boolean reviewRequired, String reviewReason, long usageCount,
                          String repoOwner, String repoName, Validation validation) {

        public String currentReference() {
            return LessonReference.revision(reference, current.number());
        }
    }

    /**
     * @param revisions      every revision, oldest first
     * @param suggestedPaths files the cited findings point at, offered as a narrower scope while
     *                       the current revision applies repository-wide
     */
    public record Detail(Summary summary, List<RevisionView> revisions, List<HistoryEntry> history,
                         List<String> suggestedPaths) {
    }

    public record ListPage(List<Summary> rows, String nextCursor) {
    }

    /**
     * One active lesson as lesson retrieval sees it: the summary (whose {@code current()} is the
     * active revision, with its evidence and validation), plus the identity and wording hash of that
     * revision, so a usage record can prove exactly which text was supplied.
     */
    public record ActiveLesson(Summary summary, Long revisionId, String contentHash,
                               Instant activatedAt) {
    }

    /**
     * What a running review needs to know about a lesson revision it already received.
     *
     * @param state          the lesson's state now
     * @param activeRevision its active revision number now, or {@code null}
     * @param problems       why the supplied revision no longer validates — the same issues and
     *                       conflicts {@code learning show} reports; empty while it does
     */
    public record Standing(Long lessonId, LessonState state, Integer activeRevision,
                           List<String> problems) {

        public Standing {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * Writes candidate {@code L<n>@1} from one stored feedback record. The lesson inherits that
     * feedback's integration, bot, repository and {@code agentic-review} scope.
     *
     * @param paths validated patterns from a {@code paths:} line, or {@code null}/empty for none
     */
    public Recorded propose(FeedbackScope scope, FeedbackSource source, String feedbackReference,
                            List<String> paths, String text, String actor) {
        return mutate(source, () -> {
            String who = requireActor(actor);
            String lessonText = normalizeText(text);
            WorkflowFeedback feedback = feedbackRepository.findByReference(feedbackReference)
                    .filter(record -> sameRepository(scope, record))
                    .orElseThrow(() -> new LessonRejectedException("unknown-feedback",
                            "No feedback " + feedbackReference + " is available here."));
            requireSupporting(feedback);

            ReviewRecord review = reviewRepository.findById(feedback.getReviewId()).orElse(null);
            ReviewFinding finding = findingOf(review, feedback);

            KnowledgeLesson lesson = new KnowledgeLesson();
            lesson.setReference(referenceAllocator.next(ReferenceNamespace.LESSON));
            lesson.setGitIntegrationId(feedback.getGitIntegrationId());
            lesson.setBotId(feedback.getBotId());
            lesson.setRepoOwner(feedback.getRepoOwner());
            lesson.setRepoName(feedback.getRepoName());
            lesson.setProviderRepositoryId(feedback.getProviderRepositoryId());
            lesson.setWorkflowKey(WORKFLOW_KEY);
            lesson.setState(LessonState.CANDIDATE);
            lesson.setLatestRevision(1);
            lesson.setCreatedBy(who);
            lessonRepository.saveAndFlush(lesson);

            KnowledgeLessonRevision revision = newRevision(lesson, 1, lessonText,
                    paths == null ? List.of() : paths,
                    finding == null ? null : finding.getCategory(),
                    review == null ? null : review.getHeadSha(), who);
            addEvidence(lesson, revision, feedback);

            LearningEvent event = appendEvent(lesson, 1, LessonEventType.PROPOSED, who,
                    "Proposed from " + feedback.getReference() + " on "
                            + feedback.getTargetReference(),
                    feedback.getReference(), source, true);
            log.info("Lesson {}@1 proposed by {} from {}", lesson.getReference(), who,
                    feedback.getReference());
            return recorded(lesson, 1, event, false);
        });
    }

    /**
     * Writes the next candidate revision with new wording, a new path scope, or both; whatever is
     * not given is carried over, together with the evidence. A candidate it replaces becomes
     * {@code SUPERSEDED}; an active revision stays active until the new one is activated.
     *
     * @param paths new patterns ({@code List.of()} for repository-wide), or {@code null} to keep
     * @param text  new wording, or {@code null}/blank to keep
     */
    public Recorded revise(FeedbackScope scope, FeedbackSource source, String lessonReference,
                           List<String> paths, String text, String actor) {
        return mutate(source, () -> {
            String who = requireActor(actor);
            KnowledgeLesson lesson = requireInScope(scope, lessonReference);
            KnowledgeLessonRevision latest = requireRevision(lesson, lesson.getLatestRevision());
            String nextText = text == null || text.isBlank() ? latest.getText() : normalizeText(text);
            List<String> previousPaths = LessonPathScope.deserialize(latest.getPathScopes());
            List<String> nextPaths = paths == null ? previousPaths : paths;
            if (nextText.equals(latest.getText()) && nextPaths.equals(previousPaths)) {
                throw new LessonRejectedException("unchanged",
                        "That is exactly " + LessonReference.revision(lesson.getReference(),
                                latest.getRevisionNumber()) + "; nothing to revise.");
            }
            int number = latest.getRevisionNumber() + 1;
            String nextReference = LessonReference.revision(lesson.getReference(), number);
            if (latest.getState() == LessonState.CANDIDATE) {
                end(latest, LessonState.SUPERSEDED, who);
                appendEvent(lesson, latest.getRevisionNumber(), LessonEventType.SUPERSEDED, who,
                        "Replaced by the newer candidate", nextReference, null, false);
            }
            KnowledgeLessonRevision revision = newRevision(lesson, number, nextText, nextPaths,
                    latest.getCategory(), latest.getSourceCommitSha(), who);
            for (KnowledgeLessonEvidence cited : evidenceRepository
                    .findByRevisionIdOrderByIdAsc(latest.getId())) {
                feedbackRepository.findById(cited.getFeedbackId())
                        .ifPresent(feedback -> addEvidence(lesson, revision, feedback));
            }
            lesson.setLatestRevision(number);
            lesson.setState(lesson.getActiveRevision() != null
                    ? LessonState.ACTIVE : LessonState.CANDIDATE);
            lesson.touch();
            lessonRepository.saveAndFlush(lesson);

            String detail = LessonPathScope.expands(previousPaths, nextPaths)
                    ? "Revised; the path scope is wider than before" : "Revised";
            LearningEvent event = appendEvent(lesson, number, LessonEventType.REVISED, who, detail,
                    null, source, true);
            return recorded(lesson, number, event, false);
        });
    }

    /**
     * Activates exactly the named revision. Only the latest revision qualifies, so approving text
     * that has since been revised fails with the current reference. The activation is recorded as
     * {@link LessonActivationProvenance#MAINTAINER_CURATED}.
     */
    public Recorded activate(FeedbackScope scope, FeedbackSource source, String lessonReference,
                             int revisionNumber, String actor) {
        return mutate(source, () -> {
            String who = requireActor(actor);
            KnowledgeLesson lesson = requireInScope(scope, lessonReference);
            KnowledgeLessonRevision revision = requireRevision(lesson, revisionNumber);
            String named = LessonReference.revision(lesson.getReference(), revisionNumber);
            if (revisionNumber != lesson.getLatestRevision()) {
                throw new LessonRejectedException("obsolete-revision", named + " is obsolete: the "
                        + "current revision is " + LessonReference.revision(lesson.getReference(),
                        lesson.getLatestRevision()) + ". Read it with `learning show "
                        + lesson.getReference() + "` and activate that one.");
            }
            switch (revision.getState()) {
                case ACTIVE -> throw new LessonRejectedException("already-active",
                        named + " is already active.");
                case STALE -> throw new LessonRejectedException("stale", named + " is stale: its "
                        + "source no longer holds. Revise it to write a current candidate.");
                case REJECTED -> throw new LessonRejectedException("rejected", named + " was "
                        + "rejected. Revise the lesson to propose it again.");
                case SUPERSEDED -> throw new LessonRejectedException("obsolete-revision",
                        named + " was superseded.");
                case CANDIDATE, DISABLED -> {
                    // activatable
                }
            }
            List<EvidenceView> evidence = read(List.of(revision), false).evidence(revision);
            if (evidence.stream().noneMatch(EvidenceView::effective)) {
                throw new LessonRejectedException("unsupported", named + " has no effective "
                        + "evidence left (" + describe(evidence) + "). Propose a lesson from current "
                        + "feedback instead.");
            }
            Integer previous = lesson.getActiveRevision();
            if (previous != null && previous != revisionNumber) {
                KnowledgeLessonRevision replaced = requireRevision(lesson, previous);
                end(replaced, LessonState.SUPERSEDED, who);
                appendEvent(lesson, previous, LessonEventType.SUPERSEDED, who,
                        "Replaced by the activated revision", named, null, false);
            }
            revision.setState(LessonState.ACTIVE);
            revision.setActivationProvenance(LessonActivationProvenance.MAINTAINER_CURATED);
            revision.setActivatedBy(who);
            revision.setActivatedAt(Instant.now());
            revision.setEndedBy(null);
            revision.setEndedAt(null);
            revisionRepository.save(revision);

            lesson.setActiveRevision(revisionNumber);
            lesson.setState(LessonState.ACTIVE);
            // Activating is the maintainer's explicit look at the lesson's current evidence.
            lesson.setReviewRequired(false);
            lesson.setReviewReason(null);
            lesson.touch();
            lessonRepository.saveAndFlush(lesson);

            LearningEvent event = appendEvent(lesson, revisionNumber, LessonEventType.ACTIVATED, who,
                    "Activated as maintainer-curated guidance", null, source, true);
            log.info("Lesson {} activated by {}", named, who);
            return recorded(lesson, revisionNumber, event, false);
        });
    }

    /**
     * Stops future use of the lesson. The active revision becomes {@code DISABLED} and a waiting
     * candidate {@code REJECTED}; text, evidence and history stay.
     */
    public Recorded disable(FeedbackScope scope, FeedbackSource source, String lessonReference,
                            String reason, String actor) {
        return mutate(source, () -> {
            String who = requireActor(actor);
            KnowledgeLesson lesson = requireInScope(scope, lessonReference);
            if (lesson.getActiveRevision() == null) {
                throw lesson.getState() == LessonState.DISABLED
                        ? new LessonRejectedException("already-disabled",
                                lesson.getReference() + " is already disabled.")
                        : new LessonRejectedException("not-active", lesson.getReference()
                                + " is not active (" + lesson.getState().label() + "). Turn down a "
                                + "candidate with `learning reject " + LessonReference.revision(
                                lesson.getReference(), lesson.getLatestRevision()) + "`.");
            }
            int activeNumber = lesson.getActiveRevision();
            end(requireRevision(lesson, activeNumber), LessonState.DISABLED, who);
            if (lesson.getLatestRevision() != activeNumber) {
                KnowledgeLessonRevision waiting = requireRevision(lesson, lesson.getLatestRevision());
                if (waiting.getState() == LessonState.CANDIDATE) {
                    end(waiting, LessonState.REJECTED, who);
                    appendEvent(lesson, waiting.getRevisionNumber(), LessonEventType.REJECTED, who,
                            "Rejected because the lesson was disabled", null, null, false);
                }
            }
            lesson.setActiveRevision(null);
            lesson.setState(LessonState.DISABLED);
            lesson.touch();
            lessonRepository.saveAndFlush(lesson);

            LearningEvent event = appendEvent(lesson, activeNumber, LessonEventType.DISABLED, who,
                    reason == null || reason.isBlank() ? "Disabled" : reason.trim(), null, source,
                    true);
            log.info("Lesson {} disabled by {}", lesson.getReference(), who);
            return recorded(lesson, activeNumber, event, false);
        });
    }

    /** Turns down a candidate revision; an active revision of the same lesson is unaffected. */
    public Recorded reject(FeedbackScope scope, FeedbackSource source, String lessonReference,
                           int revisionNumber, String actor) {
        return mutate(source, () -> {
            String who = requireActor(actor);
            KnowledgeLesson lesson = requireInScope(scope, lessonReference);
            KnowledgeLessonRevision revision = requireRevision(lesson, revisionNumber);
            String named = LessonReference.revision(lesson.getReference(), revisionNumber);
            if (revision.getState() != LessonState.CANDIDATE) {
                throw new LessonRejectedException("not-candidate", named + " is "
                        + revision.getState().label() + ", not a candidate."
                        + (revision.getState() == LessonState.ACTIVE
                        ? " Use `learning disable " + lesson.getReference() + "` instead." : ""));
            }
            end(revision, LessonState.REJECTED, who);
            if (lesson.getActiveRevision() == null) {
                lesson.setState(LessonState.REJECTED);
            }
            lesson.touch();
            lessonRepository.saveAndFlush(lesson);
            LearningEvent event = appendEvent(lesson, revisionNumber, LessonEventType.REJECTED, who,
                    "Rejected", null, source, true);
            return recorded(lesson, revisionNumber, event, false);
        });
    }

    /**
     * Retires the active revision because its source no longer holds (a supporting file changed, a
     * recheck found it outdated). For retrieval and recheck jobs, not for Git commands: the lesson
     * must be revised before it can be used again.
     */
    @Transactional
    public void markStale(Long lessonId, String reason, String actor) {
        KnowledgeLesson lesson = lessonRepository.findById(lessonId).orElseThrow(
                () -> new IllegalArgumentException("No lesson " + lessonId));
        if (lesson.getActiveRevision() == null) {
            return;
        }
        int number = lesson.getActiveRevision();
        end(requireRevision(lesson, number), LessonState.STALE, actor);
        lesson.setActiveRevision(null);
        lesson.setState(LessonState.STALE);
        lesson.touch();
        lessonRepository.saveAndFlush(lesson);
        appendEvent(lesson, number, LessonEventType.MARKED_STALE, actor, reason, null, null, false);
    }

    /**
     * Flags every lesson citing withdrawn feedback for a maintainer's review. Runs inside the
     * transaction that withdraws the feedback, so the flag and the withdrawal commit together.
     * Lessons stay as they are otherwise — whether the guidance still holds is a human decision.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onFeedbackWithdrawn(FeedbackWithdrawnEvent withdrawn) {
        if (withdrawn.feedbackId() == null) {
            return;
        }
        Set<Long> lessonIds = new LinkedHashSet<>();
        for (KnowledgeLessonEvidence cited : evidenceRepository.findByFeedbackId(withdrawn.feedbackId())) {
            lessonIds.add(cited.getLessonId());
        }
        for (Long lessonId : lessonIds) {
            lessonRepository.findById(lessonId).ifPresent(lesson -> {
                lesson.setReviewRequired(true);
                lesson.setReviewReason(bound(withdrawn.describe(), MAX_REVIEW_REASON_CHARS));
                lesson.touch();
                lessonRepository.save(lesson);
                appendEvent(lesson, null, LessonEventType.EVIDENCE_WITHDRAWN,
                        withdrawn.actor() == null ? "unknown" : withdrawn.actor(),
                        withdrawn.describe(), withdrawn.feedbackReference(), null, false);
                log.info("Lesson {} flagged for review: {}", lesson.getReference(),
                        withdrawn.describe());
            });
        }
    }

    // ------------------------------------------------------------------ confirmations

    /** Records how the confirmation of a pending event went. */
    @Transactional
    public void markAcknowledgement(Long eventId, FeedbackAcknowledgement outcome) {
        if (eventId == null || outcome == null || outcome == FeedbackAcknowledgement.PENDING) {
            return;
        }
        eventRepository.transitionAcknowledgement(eventId, FeedbackAcknowledgement.PENDING, outcome,
                Instant.now());
    }

    /** Claims a refused confirmation for one more attempt; only one caller wins. */
    @Transactional
    public boolean claimAcknowledgementRetry(Long eventId) {
        return eventId != null && eventRepository.transitionAcknowledgement(eventId,
                FeedbackAcknowledgement.FAILED, FeedbackAcknowledgement.PENDING, Instant.now()) == 1;
    }

    // ------------------------------------------------------------------ reading

    /** One lesson with every revision, its evidence and history — readable anywhere in its repository. */
    @Transactional(readOnly = true)
    public Optional<Detail> show(FeedbackScope scope, String lessonReference) {
        return findInScope(scope, lessonReference).map(lesson -> {
            List<KnowledgeLessonRevision> rows =
                    revisionRepository.findByLessonIdOrderByRevisionNumberAsc(lesson.getId());
            Reads reads = read(rows, true);
            List<RevisionView> revisions = rows.stream().map(row -> view(row, reads)).toList();
            Summary summary = summarize(lesson, revisions, reads, Instant.now());
            List<HistoryEntry> history = eventRepository.findByLessonIdOrderByIdAsc(lesson.getId())
                    .stream()
                    .map(event -> new HistoryEntry(event.getEventType(), event.getRevisionNumber(),
                            event.getActor(), event.getDetail(), event.getRelatedReference(),
                            event.getCreatedAt()))
                    .toList();
            List<String> suggested = summary.current().repositoryWide()
                    ? suggestedPaths(rows.stream()
                            .filter(row -> row.getRevisionNumber() == summary.current().number())
                            .findFirst().orElseThrow())
                    : List.of();
            return new Detail(summary, revisions, history, suggested);
        });
    }

    /**
     * One page of this repository's lessons, newest first.
     *
     * @param state {@code null} for every state; otherwise only lessons in that state
     */
    @Transactional(readOnly = true)
    public ListPage list(FeedbackScope scope, LessonState state, Integer pageSize, String cursor) {
        int limit = pageSize == null ? MAX_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        long beforeId = cursor == null ? Long.MAX_VALUE : LessonCursor.decode(cursor, scope, state);
        Set<LessonState> states = state == null ? EnumSet.allOf(LessonState.class) : EnumSet.of(state);
        List<KnowledgeLesson> rows = lessonRepository.page(scope.gitIntegrationId(), scope.botId(),
                scope.repoOwner(), scope.repoName(), WORKFLOW_KEY, states, beforeId,
                PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        List<KnowledgeLesson> page = rows.stream().limit(limit).toList();
        Map<Long, List<KnowledgeLessonRevision>> revisionsByLesson = revisionsOf(page);
        Reads reads = read(revisionsByLesson.values().stream().flatMap(List::stream).toList(), true);
        Instant now = Instant.now();
        List<Summary> summaries = new ArrayList<>();
        for (KnowledgeLesson lesson : page) {
            List<RevisionView> revisions = revisionsByLesson.getOrDefault(lesson.getId(), List.of())
                    .stream().map(row -> view(row, reads)).toList();
            summaries.add(summarize(lesson, revisions, reads, now));
        }
        String next = more ? LessonCursor.encode(scope, state, summaries.getLast().id()) : null;
        return new ListPage(summaries, next);
    }

    /**
     * The repository's lessons that currently have an active revision, newest first and at most
     * {@code limit}, with the same validation {@code learning show} reports. Filtering on that
     * validation is the caller's decision; nothing is excluded here.
     *
     * <p>Read at the start of every agentic review and follow-up, so it takes a fixed number of
     * queries whatever the number of lessons, revisions and evidence.</p>
     */
    @Transactional(readOnly = true)
    public List<ActiveLesson> activeLessons(FeedbackScope scope, int limit) {
        if (scope == null || scope.gitIntegrationId() == null || scope.botId() == null) {
            return List.of();
        }
        List<KnowledgeLesson> rows = lessonRepository.page(scope.gitIntegrationId(), scope.botId(),
                scope.repoOwner(), scope.repoName(), WORKFLOW_KEY, EnumSet.of(LessonState.ACTIVE),
                Long.MAX_VALUE, PageRequest.of(0, Math.max(1, limit)));
        List<KnowledgeLesson> lessons = rows.stream()
                .filter(lesson -> lesson.getActiveRevision() != null && inScope(scope, lesson))
                .toList();
        Map<Long, List<KnowledgeLessonRevision>> revisionsByLesson = revisionsOf(lessons);
        Reads reads = read(revisionsByLesson.values().stream().flatMap(List::stream).toList(), true);
        Instant now = Instant.now();
        List<ActiveLesson> active = new ArrayList<>();
        for (KnowledgeLesson lesson : lessons) {
            List<KnowledgeLessonRevision> revisions =
                    revisionsByLesson.getOrDefault(lesson.getId(), List.of());
            KnowledgeLessonRevision activeRow = revisions.stream()
                    .filter(row -> row.getRevisionNumber() == lesson.getActiveRevision())
                    .findFirst().orElse(null);
            if (activeRow == null) {
                continue;
            }
            List<RevisionView> views = revisions.stream().map(row -> view(row, reads)).toList();
            active.add(new ActiveLesson(summarize(lesson, views, reads, now), activeRow.getId(),
                    activeRow.getContentHash(), activeRow.getActivatedAt()));
        }
        return active;
    }

    /**
     * Summaries — with the validation {@code learning show} reports — of the given lessons, in the
     * order given, for listings that span repositories (the admin panel). A fixed number of queries
     * whatever the number of lessons; ids that do not exist are left out. Callers decide the scope.
     */
    @Transactional(readOnly = true)
    public List<Summary> summaries(List<Long> lessonIds) {
        if (lessonIds == null || lessonIds.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeLesson> byId = new HashMap<>();
        for (KnowledgeLesson lesson : lessonRepository.findAllById(lessonIds)) {
            byId.put(lesson.getId(), lesson);
        }
        List<KnowledgeLesson> lessons = lessonIds.stream().map(byId::get).filter(Objects::nonNull)
                .toList();
        Map<Long, List<KnowledgeLessonRevision>> revisionsByLesson = revisionsOf(lessons);
        Reads reads = read(revisionsByLesson.values().stream().flatMap(List::stream).toList(), true);
        Instant now = Instant.now();
        List<Summary> summaries = new ArrayList<>(lessons.size());
        for (KnowledgeLesson lesson : lessons) {
            List<RevisionView> views = revisionsByLesson.getOrDefault(lesson.getId(), List.of())
                    .stream().map(row -> view(row, reads)).toList();
            summaries.add(summarize(lesson, views, reads, now));
        }
        return summaries;
    }

    /**
     * The current standing of lesson revisions a running review received, for its round-boundary
     * revocation check: the same validation {@link #activeLessons} applies at selection, but only
     * for the given revisions and with a fixed number of queries.
     *
     * @param revisionIdsByLesson lesson id → id of the revision row the review received
     * @return standings of the lessons that still exist in this scope; a missing id means gone
     */
    @Transactional(readOnly = true)
    public Map<Long, Standing> standing(FeedbackScope scope, Map<Long, Long> revisionIdsByLesson) {
        if (scope == null || scope.gitIntegrationId() == null || scope.botId() == null
                || revisionIdsByLesson == null || revisionIdsByLesson.isEmpty()) {
            return Map.of();
        }
        Map<Long, KnowledgeLessonRevision> supplied = new HashMap<>();
        for (KnowledgeLessonRevision revision : revisionRepository.findAllById(revisionIdsByLesson.values())) {
            supplied.put(revision.getId(), revision);
        }
        Reads reads = read(List.copyOf(supplied.values()), false);
        Instant now = Instant.now();
        Map<Long, Standing> standings = new HashMap<>();
        for (KnowledgeLesson lesson : lessonRepository.findAllById(revisionIdsByLesson.keySet())) {
            if (!inScope(scope, lesson)) {
                continue;
            }
            KnowledgeLessonRevision revision = supplied.get(revisionIdsByLesson.get(lesson.getId()));
            List<String> problems = new ArrayList<>();
            if (revision == null || !revision.getLessonId().equals(lesson.getId())) {
                problems.add("the supplied revision no longer exists");
            } else {
                Validation validation = validate(lesson, view(revision, reads), reads, now);
                problems.addAll(validation.issues());
                problems.addAll(validation.conflicts());
            }
            standings.put(lesson.getId(), new Standing(lesson.getId(), lesson.getState(),
                    lesson.getActiveRevision(), problems));
        }
        return standings;
    }

    /**
     * {@code L17@1 (active)}-style labels of every lesson revision citing the feedback, for the
     * feedback overview. Same repository only.
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> lessonsCiting(FeedbackScope scope, Long feedbackId) {
        if (feedbackId == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (KnowledgeLessonEvidence cited : evidenceRepository.findByFeedbackId(feedbackId)) {
            Optional<KnowledgeLesson> lesson = lessonRepository.findById(cited.getLessonId())
                    .filter(candidate -> inScope(scope, candidate));
            Optional<KnowledgeLessonRevision> revision = revisionRepository.findById(cited.getRevisionId());
            if (lesson.isPresent() && revision.isPresent()) {
                labels.add(LessonReference.revision(lesson.get().getReference(),
                        revision.get().getRevisionNumber()) + " (" + revision.get().getState().label()
                        + ")");
            }
        }
        return labels;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Runs one command in its own transaction. A redelivery of the same comment that loses a race
     * finds the winner's event afterwards; a concurrent change to the same lesson by another comment
     * fails the optimistic lock and is reported as such, with nothing changed.
     */
    private Recorded mutate(FeedbackSource source, Supplier<Recorded> work) {
        if (source == null || !source.isIdentifiable()) {
            // Without a comment identity a redelivery could not be told apart from a new command.
            throw new LessonRejectedException("unidentifiable-source",
                    "This command could not be attributed to a specific comment, so nothing was "
                            + "changed.");
        }
        TransactionTemplate attempt = new TransactionTemplate(transactionManager);
        attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return attempt.execute(status -> eventRepository.findBySourceKey(source.sourceKey())
                    .map(this::replayed)
                    .orElseGet(work));
        } catch (DataIntegrityViolationException | ConcurrencyFailureException conflict) {
            TransactionTemplate reread = new TransactionTemplate(transactionManager);
            reread.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reread.setReadOnly(true);
            Optional<Recorded> winner = reread.execute(status -> eventRepository
                    .findBySourceKey(source.sourceKey()).map(this::replayed));
            if (winner.isPresent()) {
                return winner.get();
            }
            log.info("Lesson command lost a concurrent change: {}", conflict.getMessage());
            throw new LessonRejectedException("concurrent-change",
                    "The lesson was changed by another command at the same moment, so nothing was "
                            + "changed. Check its current state with `learning show` and try again.");
        }
    }

    private Recorded replayed(LearningEvent event) {
        KnowledgeLesson lesson = lessonRepository.findById(event.getLessonId()).orElseThrow();
        return recorded(lesson, event.getRevisionNumber(), event, true);
    }

    private static Recorded recorded(KnowledgeLesson lesson, Integer revision, LearningEvent event,
                                     boolean duplicate) {
        return new Recorded(lesson.getReference(), revision, event.getEventType(), duplicate,
                event.getId(), event.getAcknowledgement());
    }

    private KnowledgeLessonRevision newRevision(KnowledgeLesson lesson, int number, String text,
                                                List<String> paths, String category,
                                                String sourceCommitSha, String actor) {
        KnowledgeLessonRevision revision = new KnowledgeLessonRevision();
        revision.setLessonId(lesson.getId());
        revision.setRevisionNumber(number);
        revision.setState(LessonState.CANDIDATE);
        revision.setText(text);
        revision.setPathScopes(LessonPathScope.serialize(paths));
        revision.setCategory(category == null ? null : bound(category, 64));
        revision.setSourceCommitSha(sourceCommitSha);
        Instant now = Instant.now();
        revision.setCreatedAt(now);
        revision.setRecheckAfter(now.plus(recheckPeriod));
        revision.setContentHash(sha256(text + "\n--paths--\n"
                + String.join("\n", paths)));
        revision.setCreatedBy(actor);
        return revisionRepository.saveAndFlush(revision);
    }

    private void addEvidence(KnowledgeLesson lesson, KnowledgeLessonRevision revision,
                             WorkflowFeedback feedback) {
        KnowledgeLessonEvidence row = new KnowledgeLessonEvidence();
        row.setLessonId(lesson.getId());
        row.setRevisionId(revision.getId());
        row.setFeedbackId(feedback.getId());
        row.setFeedbackReference(feedback.getReference());
        row.setTargetReference(feedback.getTargetReference());
        evidenceRepository.save(row);
    }

    private LearningEvent appendEvent(KnowledgeLesson lesson, Integer revision, LessonEventType type,
                                      String actor, String detail, String relatedReference,
                                      FeedbackSource source, boolean confirmed) {
        LearningEvent event = new LearningEvent();
        event.setLessonId(lesson.getId());
        event.setRevisionNumber(revision);
        event.setEventType(type);
        event.setActor(actor);
        event.setDetail(detail == null ? null : bound(detail, MAX_DETAIL_CHARS));
        event.setRelatedReference(relatedReference);
        event.setSourceKey(source == null ? null : source.sourceKey());
        // An admin-panel action is answered in the panel; there is no pull-request confirmation.
        boolean confirmedOnPullRequest = confirmed && (source == null || !source.isAdminUi());
        event.setAcknowledgement(confirmedOnPullRequest ? FeedbackAcknowledgement.PENDING : null);
        return eventRepository.saveAndFlush(event);
    }

    private static void end(KnowledgeLessonRevision revision, LessonState state, String actor) {
        revision.setState(state);
        revision.setEndedBy(actor);
        revision.setEndedAt(Instant.now());
    }

    private Optional<KnowledgeLesson> findInScope(FeedbackScope scope, String lessonReference) {
        return LessonReference.parse(lessonReference)
                .flatMap(reference -> lessonRepository.findByReference(reference.lesson()))
                .filter(lesson -> inScope(scope, lesson));
    }

    private KnowledgeLesson requireInScope(FeedbackScope scope, String lessonReference) {
        return findInScope(scope, lessonReference).orElseThrow(() -> new LessonRejectedException(
                "unknown-lesson", "No lesson " + lessonReference + " is available here."));
    }

    private KnowledgeLessonRevision requireRevision(KnowledgeLesson lesson, int number) {
        return revisionRepository.findByLessonIdAndRevisionNumber(lesson.getId(), number)
                .orElseThrow(() -> new LessonRejectedException("unknown-revision",
                        LessonReference.revision(lesson.getReference(), number)
                                + " does not exist; the latest revision is "
                                + LessonReference.revision(lesson.getReference(),
                                lesson.getLatestRevision()) + "."));
    }

    private static boolean inScope(FeedbackScope scope, KnowledgeLesson lesson) {
        return scope != null && scope.gitIntegrationId() != null && scope.botId() != null
                && scope.gitIntegrationId().equals(lesson.getGitIntegrationId())
                && scope.botId().equals(lesson.getBotId())
                && lesson.getRepoOwner().equalsIgnoreCase(scope.repoOwner())
                && lesson.getRepoName().equalsIgnoreCase(scope.repoName())
                && WORKFLOW_KEY.equals(lesson.getWorkflowKey());
    }

    private static boolean sameRepository(FeedbackScope scope, WorkflowFeedback record) {
        return scope != null && scope.gitIntegrationId() != null && scope.botId() != null
                && scope.gitIntegrationId().equals(record.getGitIntegrationId())
                && scope.botId().equals(record.getBotId())
                && record.getRepoOwner().equalsIgnoreCase(scope.repoOwner())
                && record.getRepoName().equalsIgnoreCase(scope.repoName());
    }

    private static void requireSupporting(WorkflowFeedback feedback) {
        if (feedback.getState() == FeedbackState.RETRACTED) {
            throw new LessonRejectedException("retracted-feedback", feedback.getReference()
                    + " was retracted, so it cannot support a lesson.");
        }
        if (feedback.getState() == FeedbackState.SUPERSEDED) {
            throw new LessonRejectedException("superseded-feedback", feedback.getReference()
                    + " was replaced by " + feedback.getSupersededBy() + "; propose from that one.");
        }
    }

    private static String requireActor(String actor) {
        String normalized = actor == null ? null : actor.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new LessonRejectedException("unknown-actor",
                    "This command could not be attributed to a commenter, so nothing was changed.");
        }
        return normalized;
    }

    static String normalizeText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new LessonRejectedException("missing-text",
                    "Write the lesson after the reference, for example `learning propose FB31 "
                            + "Before reporting a missing permission check, inspect the enclosing "
                            + "security filter.`");
        }
        if (trimmed.length() > MAX_LESSON_CHARS) {
            // A cut-off rule can lose the qualification that made it right; refuse instead.
            throw new LessonRejectedException("text-too-long", "A lesson is at most "
                    + MAX_LESSON_CHARS + " characters; this one has " + trimmed.length() + ".");
        }
        return trimmed;
    }

    private static ReviewFinding findingOf(ReviewRecord review, WorkflowFeedback feedback) {
        if (review == null || feedback.getTargetType() != FeedbackTargetType.FINDING
                || feedback.getFindingId() == null) {
            return null;
        }
        return review.getFindings().stream()
                .filter(finding -> Objects.equals(finding.getId(), feedback.getFindingId()))
                .findFirst().orElse(null);
    }

    /** Files the cited findings point at — a suggestion for narrowing, never applied silently. */
    private List<String> suggestedPaths(KnowledgeLessonRevision revision) {
        Set<String> paths = new LinkedHashSet<>();
        for (KnowledgeLessonEvidence cited : evidenceRepository.findByRevisionIdOrderByIdAsc(revision.getId())) {
            feedbackRepository.findById(cited.getFeedbackId()).ifPresent(feedback -> {
                ReviewFinding finding = findingOf(
                        reviewRepository.findById(feedback.getReviewId()).orElse(null), feedback);
                if (finding != null) {
                    for (ReviewFindingLocation location : finding.getLocations()) {
                        if (location.getPath() != null && !location.getPath().isBlank()) {
                            paths.add(location.getPath().trim());
                        }
                    }
                }
            });
        }
        return paths.stream().limit(LessonPathScope.MAX_PATTERNS).toList();
    }

    // ------------------------------------------------------------------ batched reads and validation

    /** Every revision of the lessons, in one query: lesson id → revisions in number order. */
    private Map<Long, List<KnowledgeLessonRevision>> revisionsOf(List<KnowledgeLesson> lessons) {
        if (lessons.isEmpty()) {
            return Map.of();
        }
        return revisionRepository.findByLessonIdInOrderByLessonIdAscRevisionNumberAsc(
                        lessons.stream().map(KnowledgeLesson::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(KnowledgeLessonRevision::getLessonId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * What views and validation of some revisions need, read with a fixed number of queries
     * whatever the number of revisions and evidence: the feedback their evidence cites, optionally
     * usage counts, and the other lessons resting on the same review targets.
     */
    private Reads read(List<KnowledgeLessonRevision> revisions, boolean withUsage) {
        if (revisions.isEmpty()) {
            return Reads.EMPTY;
        }
        List<KnowledgeLessonEvidence> cited = evidenceRepository.findByRevisionIdInOrderByIdAsc(
                revisions.stream().map(KnowledgeLessonRevision::getId).collect(Collectors.toSet()));
        Map<Long, WorkflowFeedback> feedback = new HashMap<>();
        if (!cited.isEmpty()) {
            for (WorkflowFeedback record : feedbackRepository.findAllById(cited.stream()
                    .map(KnowledgeLessonEvidence::getFeedbackId).collect(Collectors.toSet()))) {
                feedback.put(record.getId(), record);
            }
        }
        Map<Long, List<EvidenceView>> evidence = new HashMap<>();
        for (KnowledgeLessonEvidence row : cited) {
            evidence.computeIfAbsent(row.getRevisionId(), id -> new ArrayList<>())
                    .add(evidenceView(row, feedback.get(row.getFeedbackId())));
        }
        Map<Long, Long> usage = new HashMap<>();
        if (withUsage) {
            for (Object[] count : usageRepository.countPerRevision(revisions.stream()
                    .map(KnowledgeLessonRevision::getLessonId).collect(Collectors.toSet()))) {
                usage.put((Long) count[0], ((Number) count[1]).longValue());
            }
        }
        Set<String> targets = evidence.values().stream().flatMap(List::stream)
                .map(EvidenceView::targetReference).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<KnowledgeLessonEvidence> onTargets = targets.isEmpty() ? List.of()
                : evidenceRepository.findByTargetReferenceInOrderByIdAsc(targets);
        Map<Long, KnowledgeLesson> citing = new HashMap<>();
        if (!onTargets.isEmpty()) {
            for (KnowledgeLesson lesson : lessonRepository.findAllById(onTargets.stream()
                    .map(KnowledgeLessonEvidence::getLessonId).collect(Collectors.toSet()))) {
                if (lesson.getActiveRevision() != null) {
                    citing.put(lesson.getId(), lesson);
                }
            }
        }
        Map<Long, KnowledgeLessonRevision> citingRevisions = new HashMap<>();
        Set<Long> citingRevisionIds = onTargets.stream()
                .filter(row -> citing.containsKey(row.getLessonId()))
                .map(KnowledgeLessonEvidence::getRevisionId).collect(Collectors.toSet());
        if (!citingRevisionIds.isEmpty()) {
            for (KnowledgeLessonRevision revision : revisionRepository.findAllById(citingRevisionIds)) {
                citingRevisions.put(revision.getId(), revision);
            }
        }
        return new Reads(evidence, usage, onTargets, citing, citingRevisions);
    }

    /**
     * Everything {@link #read} loaded.
     *
     * @param onTargets       evidence rows of any lesson on the review targets the read revisions
     *                        rest on, in id order
     * @param citing          lessons of those rows that have an active revision
     * @param citingRevisions revisions of those rows, for the citing lessons
     */
    private record Reads(Map<Long, List<EvidenceView>> evidenceByRevision,
                         Map<Long, Long> usageByRevision, List<KnowledgeLessonEvidence> onTargets,
                         Map<Long, KnowledgeLesson> citing,
                         Map<Long, KnowledgeLessonRevision> citingRevisions) {

        static final Reads EMPTY = new Reads(Map.of(), Map.of(), List.of(), Map.of(), Map.of());

        List<EvidenceView> evidence(KnowledgeLessonRevision revision) {
            return List.copyOf(evidenceByRevision.getOrDefault(revision.getId(), List.of()));
        }

        long usage(KnowledgeLessonRevision revision) {
            return usageByRevision.getOrDefault(revision.getId(), 0L);
        }
    }

    private static EvidenceView evidenceView(KnowledgeLessonEvidence cited, WorkflowFeedback record) {
        return record != null
                ? new EvidenceView(record.getReference(), record.getTargetReference(),
                        record.getTargetType(), record.getVerdict(), record.getState(),
                        record.getSupersededBy(), record.getActor(), record.getPrNumber(),
                        record.getCommentKind(), record.getCommentId())
                : new EvidenceView(cited.getFeedbackReference(), cited.getTargetReference(), null,
                        null, null, null, null, null, null, null);
    }

    private static RevisionView view(KnowledgeLessonRevision row, Reads reads) {
        return new RevisionView(row.getRevisionNumber(), row.getState(), row.getText(),
                LessonPathScope.deserialize(row.getPathScopes()), row.getCategory(),
                row.getSourceCommitSha(), row.getRecheckAfter(), row.getCreatedBy(),
                row.getCreatedAt(), row.getActivationProvenance(), row.getActivatedBy(),
                row.getActivatedAt(), row.getEndedBy(), row.getEndedAt(), reads.evidence(row),
                reads.usage(row));
    }

    /** @param revisions every revision of the lesson, read through the same {@code reads} */
    private static Summary summarize(KnowledgeLesson lesson, List<RevisionView> revisions, Reads reads,
                                     Instant now) {
        Map<Integer, RevisionView> byNumber = new HashMap<>();
        long usage = 0;
        for (RevisionView revision : revisions) {
            byNumber.put(revision.number(), revision);
            usage += revision.usageCount();
        }
        RevisionView current = lesson.getActiveRevision() != null
                ? byNumber.get(lesson.getActiveRevision())
                : byNumber.get(lesson.getLatestRevision());
        Integer pending = lesson.getActiveRevision() != null
                && lesson.getLatestRevision() != lesson.getActiveRevision()
                && byNumber.containsKey(lesson.getLatestRevision())
                && byNumber.get(lesson.getLatestRevision()).state() == LessonState.CANDIDATE
                ? lesson.getLatestRevision() : null;
        return new Summary(lesson.getId(), lesson.getReference(), lesson.getState(),
                lesson.getActiveRevision(), lesson.getLatestRevision(), current, pending,
                lesson.isReviewRequired(), lesson.getReviewReason(), usage, lesson.getRepoOwner(),
                lesson.getRepoName(), validate(lesson, current, reads, now));
    }

    /**
     * The one rule set deciding whether a lesson revision may guide a review — shown by
     * {@code learning show}, applied at selection and rechecked while a review runs.
     */
    private static Validation validate(KnowledgeLesson lesson, RevisionView current, Reads reads,
                                       Instant now) {
        List<String> issues = new ArrayList<>();
        if (lesson.isReviewRequired()) {
            issues.add("review required: " + lesson.getReviewReason());
        }
        for (EvidenceView cited : current.evidence()) {
            if (cited.state() == null) {
                issues.add(cited.feedbackReference() + " no longer exists");
            } else if (!cited.effective()) {
                issues.add(cited.feedbackReference() + " is " + cited.state().name()
                        .toLowerCase(Locale.ROOT) + (cited.supersededBy() == null ? ""
                        : " (by " + cited.supersededBy() + ")"));
            }
        }
        if (current.recheckAfter() != null && current.recheckAfter().isBefore(now)
                && (current.state() == LessonState.ACTIVE || current.state() == LessonState.CANDIDATE)) {
            issues.add("recheck due since " + current.recheckAfter());
        }
        return new Validation(issues, conflicts(lesson, current, reads));
    }

    /**
     * Other active lessons of the same repository whose active revision rests on feedback about the
     * same review target: two lessons drawn from one finding may say opposite things.
     */
    private static List<String> conflicts(KnowledgeLesson lesson, RevisionView current, Reads reads) {
        Set<String> targets = new LinkedHashSet<>();
        for (EvidenceView cited : current.evidence()) {
            targets.add(cited.targetReference());
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (KnowledgeLessonEvidence other : reads.onTargets()) {
            if (other.getLessonId().equals(lesson.getId())
                    || !targets.contains(other.getTargetReference())) {
                continue;
            }
            KnowledgeLesson candidate = reads.citing().get(other.getLessonId());
            KnowledgeLessonRevision revision = reads.citingRevisions().get(other.getRevisionId());
            if (candidate != null && sameScope(lesson, candidate) && revision != null
                    && revision.getRevisionNumber() == candidate.getActiveRevision()) {
                conflicts.add(LessonReference.revision(candidate.getReference(),
                        revision.getRevisionNumber()) + " also rests on " + other.getTargetReference());
            }
        }
        return List.copyOf(conflicts);
    }

    private static boolean sameScope(KnowledgeLesson a, KnowledgeLesson b) {
        return Objects.equals(a.getGitIntegrationId(), b.getGitIntegrationId())
                && Objects.equals(a.getBotId(), b.getBotId())
                && a.getRepoOwner().equalsIgnoreCase(b.getRepoOwner())
                && a.getRepoName().equalsIgnoreCase(b.getRepoName())
                && Objects.equals(a.getWorkflowKey(), b.getWorkflowKey());
    }

    private static String describe(List<EvidenceView> evidence) {
        List<String> parts = new ArrayList<>();
        for (EvidenceView cited : evidence) {
            parts.add(cited.feedbackReference() + " "
                    + (cited.state() == null ? "missing" : cited.state().name().toLowerCase(Locale.ROOT)));
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String bound(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
