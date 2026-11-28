package org.remus.giteabot.learning.feedback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.review.structured.ReferenceAllocator;
import org.remus.giteabot.review.structured.ReferenceNamespace;
import org.remus.giteabot.review.structured.ReviewFinding;
import org.remus.giteabot.review.structured.ReviewRecord;
import org.remus.giteabot.review.structured.ReviewRecordRepository;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.remus.giteabot.review.structured.WorkflowOutput;
import org.remus.giteabot.review.structured.WorkflowOutputRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Records, corrects and reports feedback on agentic-review targets.
 *
 * <p>Resolution happens in the caller's scope before any text is loaded: an output that belongs to
 * another bot, host, repository or pull request is indistinguishable from a reference that does not
 * exist. Only finalized {@code agentic-review} reviews are targets — drafts, other workflows and
 * withdrawn findings are refused with a message rather than silently accepted.</p>
 *
 * <p>Writing is append-only in meaning: a later comment by the same actor supersedes that actor's
 * earlier assessment of the same target, and retraction is a state transition. A webhook redelivery
 * resolves to the existing record through the normalized source key, so one comment is one record.</p>
 *
 * <p>Retracting or superseding a record publishes a {@link FeedbackWithdrawnEvent} inside the same
 * transaction, so whatever rests on that feedback (lessons citing it) is flagged in the same commit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    public static final int MAX_PAGE_SIZE = 20;

    private final WorkflowFeedbackRepository feedbackRepository;
    private final WorkflowFeedbackEventRepository eventRepository;
    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;
    private final ReferenceAllocator referenceAllocator;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * One accepted assessment. {@code duplicate} means this comment was already recorded — the
     * caller must not confirm it twice. {@code acknowledgementEventId} names the event whose
     * confirmation the caller tracks, with its {@code acknowledgement} state at the time of the call.
     */
    public record Recorded(String reference, String targetReference, FeedbackTargetType targetType,
                           FeedbackVerdict verdict, String actor, boolean duplicate,
                           Long acknowledgementEventId, FeedbackAcknowledgement acknowledgement) {

        public Recorded(String reference, String targetReference, FeedbackTargetType targetType,
                        FeedbackVerdict verdict, String actor, boolean duplicate) {
            this(reference, targetReference, targetType, verdict, actor, duplicate, null, null);
        }
    }

    /** One page of a listing; {@code nextCursor} is {@code null} on the last page. */
    public record ListPage(List<Summary> rows, String nextCursor) {
    }

    public record HistoryEntry(FeedbackEventType type, String actor, String relatedReference,
                               Instant at) {
    }

    public record Detail(Summary summary, List<HistoryEntry> history) {
    }

    public record Summary(Long id, String reference, String outputReference, String targetReference,
                          FeedbackTargetType targetType, FeedbackVerdict verdict, FeedbackState state,
                          String actor, String reason, Long prNumber, String repoOwner,
                          String repoName, String supersededBy, Instant createdAt,
                          String commentKind, String commentId) {
    }

    /**
     * Records one assessment. The attempt runs in its own transaction: when a parallel delivery of
     * the same comment wins the unique source key, that transaction is rolled back (on PostgreSQL it
     * is aborted outright), so the winner's record is read afterwards in a fresh one.
     */
    public Recorded submit(FeedbackScope scope, FeedbackSource source, FeedbackTarget target,
                           FeedbackVerdict verdict, String reason, String actor) {
        TransactionTemplate attempt = new TransactionTemplate(transactionManager);
        attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return attempt.execute(status -> submitOnce(scope, source, target, verdict, reason, actor));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // A parallel delivery of the same comment won the unique index; its record is the
            // answer, and this attempt must not add a second one or claim to have created it.
            TransactionTemplate reread = new TransactionTemplate(transactionManager);
            reread.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reread.setReadOnly(true);
            Optional<Recorded> winner = reread.execute(status -> feedbackRepository
                    .findBySourceKey(source.sourceKey())
                    .map(record -> replayed(record, FeedbackEventType.SUBMITTED)));
            if (winner.isEmpty()) {
                throw concurrentDuplicate;
            }
            return winner.get();
        }
    }

    private Recorded submitOnce(FeedbackScope scope, FeedbackSource source, FeedbackTarget target,
                            FeedbackVerdict verdict, String reason, String actor) {
        if (verdict == null || target == null) {
            throw new FeedbackRejectedException("incomplete-command",
                    "A feedback command needs a review target and a verdict.");
        }
        if (source == null || !source.isIdentifiable()) {
            // Without a comment identity a redelivery could not be told apart from a new comment;
            // recording anyway would silently accept duplicates, so this is refused.
            throw new FeedbackRejectedException("unidentifiable-source",
                    "This command could not be attributed to a specific comment, so it was not "
                            + "recorded.");
        }
        String normalizedActor = actor == null ? null : actor.trim();
        if (normalizedActor == null || normalizedActor.isEmpty()) {
            throw new FeedbackRejectedException("unknown-actor",
                    "This command could not be attributed to a commenter, so it was not recorded.");
        }
        Optional<WorkflowFeedback> replay = feedbackRepository.findBySourceKey(source.sourceKey());
        if (replay.isPresent()) {
            return replayed(replay.get(), FeedbackEventType.SUBMITTED);
        }

        Resolved resolved = resolve(scope, target);
        Long findingId = resolved.finding() == null ? null : resolved.finding().getId();
        String reference = referenceAllocator.next(ReferenceNamespace.FEEDBACK);
        supersedeEffective(resolved.output().getId(), target.type(), findingId, normalizedActor,
                reference);

        WorkflowFeedback record = new WorkflowFeedback();
        record.setReference(reference);
        record.setOutputId(resolved.output().getId());
        record.setReviewId(resolved.review().getId());
        record.setTargetType(target.type());
        record.setTargetReference(target.reference());
        record.setFindingId(findingId);
        record.setVerdict(verdict);
        record.setReason(normalizeReason(reason));
        record.setActor(normalizedActor);
        record.setState(FeedbackState.EFFECTIVE);
        record.setGitIntegrationId(scope.gitIntegrationId());
        record.setBotId(scope.botId());
        record.setRepoOwner(resolved.output().getRepoOwner());
        record.setRepoName(resolved.output().getRepoName());
        record.setProviderRepositoryId(resolved.output().getProviderRepositoryId());
        record.setPrNumber(resolved.output().getPrNumber());
        record.setSubjectKind(source.subjectKind().token());
        record.setSubjectNumber(source.subjectNumber());
        record.setCommentKind(source.commentKind().toUpperCase(Locale.ROOT));
        record.setCommentId(source.commentId());
        record.setSourceKey(source.sourceKey());

        feedbackRepository.saveAndFlush(record);
        WorkflowFeedbackEvent submitted = appendEvent(record.getId(), FeedbackEventType.SUBMITTED,
                normalizedActor, null, FeedbackAcknowledgement.PENDING);

        log.info("Recorded feedback {} ({}) on {} by {}", reference, verdict.token(),
                target.reference(), normalizedActor);
        return recorded(record, false, submitted);
    }

    @Transactional
    public Recorded retract(FeedbackScope scope, String reference, String actor) {
        return retract(scope, reference, actor, false);
    }

    /**
     * Withdraws a record. Its author may always retract it; {@code actorIsLearningMaintainer} — the
     * caller's standing under the bot's <em>current</em> learning-maintainer list — additionally
     * lets a maintainer retract feedback that should not support lessons.
     */
    @Transactional
    public Recorded retract(FeedbackScope scope, String reference, String actor,
                            boolean actorIsLearningMaintainer) {
        return withdraw(scope, reference, actor, actorIsLearningMaintainer,
                FeedbackAcknowledgement.PENDING);
    }

    /**
     * Withdraws a record from the admin panel, with a learning maintainer's standing. The retraction
     * is answered in the panel, so no pull-request confirmation is left pending.
     */
    @Transactional
    public Recorded retractFromAdminPanel(FeedbackScope scope, String reference, String actor) {
        return withdraw(scope, reference, actor, true, null);
    }

    private Recorded withdraw(FeedbackScope scope, String reference, String actor,
                              boolean actorIsLearningMaintainer,
                              FeedbackAcknowledgement acknowledgement) {
        WorkflowFeedback record = requireInScope(scope, reference);
        String normalizedActor = actor == null ? null : actor.trim();
        boolean author = normalizedActor != null && normalizedActor.equalsIgnoreCase(record.getActor());
        if (normalizedActor == null || normalizedActor.isEmpty()
                || !(author || actorIsLearningMaintainer)) {
            throw new FeedbackRejectedException("not-author",
                    "Only the author of " + record.getReference() + " or a learning maintainer can "
                            + "retract it.");
        }
        if (record.getState() == FeedbackState.RETRACTED) {
            return replayed(record, FeedbackEventType.RETRACTED);
        }
        if (record.getState() == FeedbackState.SUPERSEDED) {
            throw new FeedbackRejectedException("superseded",
                    record.getReference() + " was already replaced by " + record.getSupersededBy()
                            + "; retract that one instead.");
        }
        record.setState(FeedbackState.RETRACTED);
        WorkflowFeedbackEvent retracted = appendEvent(record.getId(), FeedbackEventType.RETRACTED,
                normalizedActor, null, acknowledgement);
        eventPublisher.publishEvent(new FeedbackWithdrawnEvent(record.getId(), record.getReference(),
                FeedbackState.RETRACTED, null, normalizedActor));
        return recorded(record, false, retracted);
    }

    /** Records how the confirmation of a pending event went. */
    @Transactional
    public void markAcknowledgement(Long eventId, FeedbackAcknowledgement outcome) {
        if (eventId == null || outcome == null || outcome == FeedbackAcknowledgement.PENDING) {
            return;
        }
        eventRepository.transitionAcknowledgement(eventId, FeedbackAcknowledgement.PENDING, outcome,
                Instant.now());
    }

    /**
     * Claims a refused confirmation for one more attempt. Only one caller wins; an uncertain or
     * pending confirmation is never claimed, since it may already be on the pull request.
     */
    @Transactional
    public boolean claimAcknowledgementRetry(Long eventId) {
        return eventId != null && eventRepository.transitionAcknowledgement(eventId,
                FeedbackAcknowledgement.FAILED, FeedbackAcknowledgement.PENDING, Instant.now()) == 1;
    }

    /**
     * One page, newest first. {@code cursor} is the opaque continuation of a previous page of the
     * same listing; a cursor of another listing is refused.
     */
    @Transactional(readOnly = true)
    public ListPage list(FeedbackScope scope, boolean repositoryWide, Integer pageSize,
                         String cursor) {
        int limit = pageSize == null ? MAX_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        long beforeId = cursor == null ? Long.MAX_VALUE
                : FeedbackCursor.decode(cursor, scope, repositoryWide);
        PageRequest page = PageRequest.of(0, limit + 1);
        List<WorkflowFeedback> rows = repositoryWide
                ? feedbackRepository.pageForRepository(scope.gitIntegrationId(), scope.botId(),
                        scope.repoOwner(), scope.repoName(), beforeId, page)
                : feedbackRepository.pageForPullRequest(scope.gitIntegrationId(), scope.botId(),
                        scope.repoOwner(), scope.repoName(), scope.prNumber(), beforeId, page);
        boolean more = rows.size() > limit;
        List<Summary> summaries = rows.stream().limit(limit).map(FeedbackService::toSummary).toList();
        String next = more
                ? FeedbackCursor.encode(scope, repositoryWide, summaries.getLast().id()) : null;
        return new ListPage(summaries, next);
    }

    /** One stored record with its history, readable anywhere in the same bot/repository scope. */
    @Transactional(readOnly = true)
    public Optional<Detail> show(FeedbackScope scope, String reference) {
        return feedbackRepository.findByReference(reference)
                .filter(record -> sameRepository(scope, record))
                .map(record -> new Detail(toSummary(record),
                        eventRepository.findByFeedbackIdOrderByIdAsc(record.getId()).stream()
                                .map(event -> new HistoryEntry(event.getEventType(),
                                        event.getActor(), event.getRelatedReference(),
                                        event.getCreatedAt()))
                                .toList()));
    }

    // ------------------------------------------------------------------ internals

    private record Resolved(WorkflowOutput output, ReviewRecord review, ReviewFinding finding) {
    }

    private Resolved resolve(FeedbackScope scope, FeedbackTarget target) {
        WorkflowOutput output = outputRepository.findByReference(target.outputReference())
                .filter(scope::matches)
                .orElseThrow(() -> unknownTarget(target));
        if (!StructuredReviewService.WORKFLOW_KEY.equals(output.getWorkflowKey())) {
            throw unknownTarget(target);
        }
        ReviewRecord review = reviewRepository.findByOutputId(output.getId())
                .orElseThrow(() -> unknownTarget(target));
        if (review.getState() != ReviewState.FINALIZED) {
            throw new FeedbackRejectedException("not-finalized",
                    target.outputReference() + " is not a finalized review, so it cannot be judged.");
        }
        return switch (target.type()) {
            case OUTPUT -> new Resolved(output, review, null);
            case ASSESSMENT -> {
                if (review.getAssessment() == null) {
                    throw new FeedbackRejectedException("no-assessment",
                            target.reference() + " has no assessment to judge.");
                }
                yield new Resolved(output, review, null);
            }
            case FINDING -> new Resolved(output, review, requireActiveFinding(review, target));
        };
    }

    private static ReviewFinding requireActiveFinding(ReviewRecord review, FeedbackTarget target) {
        ReviewFinding finding = review.getFindings().stream()
                .filter(candidate -> candidate.getOrdinal() == target.findingOrdinal())
                .findFirst()
                .orElseThrow(() -> new FeedbackRejectedException("unknown-finding",
                        "Finding " + target.reference() + " does not exist in that review."));
        if (finding.isWithdrawn()) {
            throw new FeedbackRejectedException("withdrawn-finding",
                    "Finding " + target.reference() + " was withdrawn from the review and cannot "
                            + "receive feedback.");
        }
        return finding;
    }

    private static FeedbackRejectedException unknownTarget(FeedbackTarget target) {
        return new FeedbackRejectedException("unknown-target",
                "No review " + target.outputReference() + " is available here.");
    }

    private void supersedeEffective(Long outputId, FeedbackTargetType targetType, Long findingId,
                                    String actor, String supersededBy) {
        for (WorkflowFeedback previous : feedbackRepository.findEffective(outputId, targetType,
                findingId, actor)) {
            previous.setState(FeedbackState.SUPERSEDED);
            previous.setSupersededBy(supersededBy);
            appendEvent(previous.getId(), FeedbackEventType.SUPERSEDED, actor, supersededBy, null);
            eventPublisher.publishEvent(new FeedbackWithdrawnEvent(previous.getId(),
                    previous.getReference(), FeedbackState.SUPERSEDED, supersededBy, actor));
        }
    }

    private WorkflowFeedback requireInScope(FeedbackScope scope, String reference) {
        return feedbackRepository.findByReference(reference)
                .filter(record -> sameRepository(scope, record))
                .orElseThrow(() -> new FeedbackRejectedException("unknown-feedback",
                        "No feedback " + reference + " is available here."));
    }

    private static boolean sameRepository(FeedbackScope scope, WorkflowFeedback record) {
        return scope != null && scope.gitIntegrationId() != null && scope.botId() != null
                && scope.gitIntegrationId().equals(record.getGitIntegrationId())
                && scope.botId().equals(record.getBotId())
                && record.getRepoOwner().equalsIgnoreCase(scope.repoOwner())
                && record.getRepoName().equalsIgnoreCase(scope.repoName());
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= FeedbackCommandParser.MAX_REASON_CHARS
                ? trimmed : trimmed.substring(0, FeedbackCommandParser.MAX_REASON_CHARS);
    }

    private WorkflowFeedbackEvent appendEvent(Long feedbackId, FeedbackEventType type, String actor,
                                              String relatedReference,
                                              FeedbackAcknowledgement acknowledgement) {
        WorkflowFeedbackEvent event = new WorkflowFeedbackEvent();
        event.setFeedbackId(feedbackId);
        event.setEventType(type);
        event.setActor(actor);
        event.setRelatedReference(relatedReference);
        event.setAcknowledgement(acknowledgement);
        return eventRepository.save(event);
    }

    private Recorded replayed(WorkflowFeedback record, FeedbackEventType confirmedEvent) {
        return recorded(record, true, eventRepository
                .findFirstByFeedbackIdAndEventTypeOrderByIdDesc(record.getId(), confirmedEvent)
                .orElse(null));
    }

    private static Recorded recorded(WorkflowFeedback record, boolean duplicate,
                                     WorkflowFeedbackEvent event) {
        return new Recorded(record.getReference(), record.getTargetReference(),
                record.getTargetType(), record.getVerdict(), record.getActor(), duplicate,
                event == null ? null : event.getId(),
                event == null ? null : event.getAcknowledgement());
    }

    private static Summary toSummary(WorkflowFeedback record) {
        return new Summary(record.getId(), record.getReference(),
                outputReferenceOf(record.getTargetReference()),
                record.getTargetReference(), record.getTargetType(), record.getVerdict(),
                record.getState(), record.getActor(), record.getReason(), record.getPrNumber(),
                record.getRepoOwner(), record.getRepoName(), record.getSupersededBy(),
                record.getCreatedAt(), record.getCommentKind(), record.getCommentId());
    }

    /**
     * {@code O1234}, {@code O1234-F2} and {@code O1234-A} all name a review whose own reference is
     * the part before the first suffix separator.
     */
    static String outputReferenceOf(String targetReference) {
        if (targetReference == null) {
            return null;
        }
        int dash = targetReference.indexOf('-');
        return dash < 0 ? targetReference : targetReference.substring(0, dash);
    }
}
