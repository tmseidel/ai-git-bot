package org.remus.giteabot.learning.feedback;

import jakarta.persistence.EntityManager;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    public static final int MAX_PAGE_SIZE = 20;

    private final WorkflowFeedbackRepository feedbackRepository;
    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;
    private final ReferenceAllocator referenceAllocator;
    private final EntityManager entityManager;

    /**
     * One accepted assessment. {@code duplicate} means this comment was already recorded — the
     * caller must not confirm it twice.
     */
    public record Recorded(String reference, String targetReference, FeedbackTargetType targetType,
                           FeedbackVerdict verdict, String actor, boolean duplicate) {
    }

    public record Summary(Long id, String reference, String outputReference, String targetReference,
                          FeedbackTargetType targetType, FeedbackVerdict verdict, FeedbackState state,
                          String actor, String reason, Long prNumber, String repoOwner,
                          String repoName, String supersededBy, Instant createdAt) {
    }

    @Transactional
    public Recorded submit(FeedbackScope scope, FeedbackSource source, FeedbackTarget target,
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
            return recorded(replay.get(), true);
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

        try {
            feedbackRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // A parallel delivery of the same comment won the unique index; its record is the
            // answer, and this attempt must not add a second one or claim to have created it.
            entityManager.clear();
            return feedbackRepository.findBySourceKey(source.sourceKey())
                    .map(existing -> recorded(existing, true))
                    .orElseThrow(() -> new FeedbackRejectedException("duplicate-race",
                            "This comment was being recorded concurrently; check `feedback list` "
                                    + "before retrying."));
        }

        log.info("Recorded feedback {} ({}) on {} by {}", reference, verdict.token(),
                target.reference(), normalizedActor);
        return recorded(record, false);
    }

    @Transactional
    public Recorded retract(FeedbackScope scope, String reference, String actor) {
        WorkflowFeedback record = requireInScope(scope, reference);
        String normalizedActor = actor == null ? null : actor.trim();
        if (normalizedActor == null || !normalizedActor.equalsIgnoreCase(record.getActor())) {
            throw new FeedbackRejectedException("not-author",
                    "Only the author of " + record.getReference() + " can retract it.");
        }
        if (record.getState() == FeedbackState.RETRACTED) {
            return recorded(record, true);
        }
        if (record.getState() == FeedbackState.SUPERSEDED) {
            throw new FeedbackRejectedException("superseded",
                    record.getReference() + " was already replaced by " + record.getSupersededBy()
                            + "; retract that one instead.");
        }
        record.setState(FeedbackState.RETRACTED);
        return recorded(record, false);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(FeedbackScope scope, boolean repositoryWide, Integer pageSize,
                              Long beforeId) {
        int limit = pageSize == null ? MAX_PAGE_SIZE : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        PageRequest page = PageRequest.of(0, limit);
        List<WorkflowFeedback> rows = repositoryWide
                ? feedbackRepository.pageForRepository(scope.gitIntegrationId(), scope.botId(),
                        scope.repoOwner(), scope.repoName(), cursor, page)
                : feedbackRepository.pageForPullRequest(scope.gitIntegrationId(), scope.botId(),
                        scope.repoOwner(), scope.repoName(), scope.prNumber(), cursor, page);
        return rows.stream().map(FeedbackService::toSummary).toList();
    }

    /** One stored record, readable anywhere in the same bot/repository scope. */
    @Transactional(readOnly = true)
    public Optional<Summary> show(FeedbackScope scope, String reference) {
        return feedbackRepository.findByReference(reference)
                .filter(record -> sameRepository(scope, record))
                .map(FeedbackService::toSummary);
    }

    // ------------------------------------------------------------------ internals

    private record Resolved(WorkflowOutput output, ReviewRecord review, ReviewFinding finding) {
    }

    private Resolved resolve(FeedbackScope scope, FeedbackTarget target) {
        WorkflowOutput output = outputRepository.findByReference(target.outputReference())
                .filter(candidate -> scope.matches(candidate))
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

    private static Recorded recorded(WorkflowFeedback record, boolean duplicate) {
        return new Recorded(record.getReference(), record.getTargetReference(),
                record.getTargetType(), record.getVerdict(), record.getActor(), duplicate);
    }

    private static Summary toSummary(WorkflowFeedback record) {
        return new Summary(record.getId(), record.getReference(),
                outputReferenceOf(record.getTargetReference()),
                record.getTargetReference(), record.getTargetType(), record.getVerdict(),
                record.getState(), record.getActor(), record.getReason(), record.getPrNumber(),
                record.getRepoOwner(), record.getRepoName(), record.getSupersededBy(),
                record.getCreatedAt());
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
