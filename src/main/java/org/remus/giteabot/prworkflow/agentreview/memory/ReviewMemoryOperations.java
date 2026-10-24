package org.remus.giteabot.prworkflow.agentreview.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.remus.giteabot.prworkflow.agentreview.memory.ReviewMemoryInputs.*;

/**
 * Side-effect-free, fully validated replacement/sealing operations. A failed operation leaves the previous value unchanged.
 * The caller supplies the store-allocated next version; this module owns no persistence, CAS, fence or counter.
 */
public final class ReviewMemoryOperations {
    private final ReviewMemoryCodec codec = new ReviewMemoryCodec();

    /**
     * Trusted application facts. Paths must come from checkpointed evidence; lineage must already have been
     * checked against run identity, revision and cycles by the store. A review has no question or lineage.
     */
    public record ApplicationMetadata(ReviewMemory.Purpose purpose, RevisionKey revisionKey, Long baseReviewRunId,
                                      Long parentMemoryRunId, String clarificationQuestion, List<String> inspectedPaths) {
        public ApplicationMetadata {
            MemoryChecks.require(purpose != null && revisionKey != null, "Missing application metadata");
            MemoryChecks.runId(baseReviewRunId);
            MemoryChecks.runId(parentMemoryRunId);
            inspectedPaths = MemoryChecks.copy(inspectedPaths, ReviewMemory.MAX_PATHS, "inspectedPaths");
            inspectedPaths.forEach(MemoryChecks::path);
            if (purpose == ReviewMemory.Purpose.REVIEW) {
                MemoryChecks.require(clarificationQuestion == null && baseReviewRunId == null && parentMemoryRunId == null,
                        "Review cannot carry clarification metadata");
            } else {
                MemoryChecks.text(clarificationQuestion, ReviewMemory.MAX_QUESTION, true, "question");
            }
        }
    }

    /** Fully replaces a review draft, allocating identities only for new findings. previous may be null. */
    public ReviewMemory updateReview(ReviewMemory previous, ApplicationMetadata metadata, long memoryVersion, UpdateReviewMemoryInput input) {
        check(previous, metadata, memoryVersion, ReviewMemory.Purpose.REVIEW);
        MemoryChecks.require(input != null, "Missing update input");
        return snapshot(metadata, memoryVersion, ReviewMemory.Status.DRAFT, null, null,
                replace(previous, input.review(), input.removals()), null, null);
    }

    /** Replaces only the answer draft and preserves the application question and lineage. */
    public ReviewMemory updateClarification(ReviewMemory previous, ApplicationMetadata metadata, long memoryVersion,
                                           UpdateClarificationMemoryInput input) {
        check(previous, metadata, memoryVersion, ReviewMemory.Purpose.CLARIFICATION);
        MemoryChecks.require(input != null, "Missing update input");
        return snapshot(metadata, memoryVersion, ReviewMemory.Status.DRAFT, null, null, null,
                new ReviewMemory.ClarificationContent(metadata.clarificationQuestion(), input.answerDraft()), null);
    }

    /** Seals a complete replacement or keeps the last draft content unchanged when context is insufficient. */
    public ReviewMemory submitReview(ReviewMemory previous, ApplicationMetadata metadata, long memoryVersion,
                                     ReviewMemory.CompletionCause cause, Instant sealedAt, SubmitReviewInput input) {
        check(previous, metadata, memoryVersion, ReviewMemory.Purpose.REVIEW);
        MemoryChecks.require(input != null, "Missing submit input");
        if (input.result() == Result.INSUFFICIENT_CONTEXT) {
            var content = previous == null ? new ReviewMemory.ReviewContent("", List.of(), List.of()) : previous.review();
            return snapshot(metadata, memoryVersion, ReviewMemory.Status.INSUFFICIENT_CONTEXT, cause, sealedAt,
                    content, null, input.insufficientReason());
        }
        return snapshot(metadata, memoryVersion, ReviewMemory.Status.COMPLETE, cause, sealedAt,
                replace(previous, input.review(), input.removals()), null, null);
    }

    /** Seals only the answer, or preserves the last diagnostic content on insufficient context. */
    public ReviewMemory submitClarification(ReviewMemory previous, ApplicationMetadata metadata, long memoryVersion,
                                            ReviewMemory.CompletionCause cause, Instant sealedAt, SubmitClarificationInput input) {
        check(previous, metadata, memoryVersion, ReviewMemory.Purpose.CLARIFICATION);
        MemoryChecks.require(input != null, "Missing submit input");
        if (input.result() == Result.INSUFFICIENT_CONTEXT) {
            var content = previous == null ? new ReviewMemory.ClarificationContent(metadata.clarificationQuestion(), "")
                    : previous.clarification();
            return snapshot(metadata, memoryVersion, ReviewMemory.Status.INSUFFICIENT_CONTEXT, cause, sealedAt,
                    null, content, input.insufficientReason());
        }
        return snapshot(metadata, memoryVersion, ReviewMemory.Status.COMPLETE, cause, sealedAt,
                null, new ReviewMemory.ClarificationContent(metadata.clarificationQuestion(), input.answer()), null);
    }

    private void check(ReviewMemory previous, ApplicationMetadata metadata, long version, ReviewMemory.Purpose purpose) {
        MemoryChecks.require(metadata != null && metadata.purpose() == purpose, "Wrong memory purpose");
        MemoryChecks.require(version > 0, "memoryVersion must be positive");
        if (previous != null) {
            codec.validate(previous);
            MemoryChecks.require(previous.status() == ReviewMemory.Status.DRAFT, "Sealed memory cannot be replaced");
            MemoryChecks.require(version > previous.memoryVersion(), "memoryVersion must advance");
            MemoryChecks.require(previous.purpose() == purpose && previous.revisionKey().equals(metadata.revisionKey())
                    && Objects.equals(previous.baseReviewRunId(), metadata.baseReviewRunId())
                    && Objects.equals(previous.parentMemoryRunId(), metadata.parentMemoryRunId()), "Application binding cannot change");
            if (purpose == ReviewMemory.Purpose.CLARIFICATION) {
                MemoryChecks.require(previous.clarification().question().equals(metadata.clarificationQuestion()), "Question cannot change");
            }
        }
    }

    private static ReviewMemory.ReviewContent replace(ReviewMemory previous, ReviewContentInput input, List<Removal> removals) {
        Set<String> existing = new HashSet<>();
        if (previous != null) {
            previous.review().findings().forEach(f -> existing.add(f.id()));
        }
        Set<String> retained = new HashSet<>();
        var findings = new ArrayList<ReviewMemory.Finding>();
        Set<String> assigned = new HashSet<>(existing);
        for (var f : input.findings()) {
            String id = f.existingId();
            if (id == null) {
                do {
                    id = UUID.randomUUID().toString();
                } while (!assigned.add(id));
            } else {
                MemoryChecks.require(existing.contains(id), "Unknown existing finding id");
                MemoryChecks.require(retained.add(id), "Duplicate existing finding id");
            }
            findings.add(new ReviewMemory.Finding(id, f.severity(), f.category(), f.title(), f.description(),
                    f.recommendation(), f.file(), f.line(), f.cwe(), f.owasp()));
        }
        Set<String> removed = new HashSet<>();
        for (var removal : removals) {
            MemoryChecks.require(existing.contains(removal.id()) && !retained.contains(removal.id()), "Invalid removal id");
            MemoryChecks.require(removed.add(removal.id()), "Duplicate removal id");
        }
        existing.removeAll(retained);
        MemoryChecks.require(existing.equals(removed), "Explicit removal required for every omitted finding");
        return new ReviewMemory.ReviewContent(input.summary(), input.openQuestions(), findings);
    }

    private ReviewMemory snapshot(ApplicationMetadata metadata, long version, ReviewMemory.Status status,
                                   ReviewMemory.CompletionCause cause, Instant sealedAt, ReviewMemory.ReviewContent review,
                                   ReviewMemory.ClarificationContent clarification, String reason) {
        var memory = new ReviewMemory(ReviewMemory.SCHEMA_VERSION, version, metadata.purpose(), status, cause,
                metadata.revisionKey(), sealedAt, metadata.baseReviewRunId(), metadata.parentMemoryRunId(),
                metadata.inspectedPaths(), review, clarification, reason);
        codec.validate(memory);
        return memory;
    }
}
