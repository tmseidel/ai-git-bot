package org.remus.giteabot.prworkflow.agentreview.memory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Immutable, purpose-discriminated ledger. Application metadata is never model input.
 * Use {@link ReviewMemoryCodec#validate(ReviewMemory)} to also check the serialized total.
 * Memory versions are supplied by the caller-owned transactional store, not allocated here.
 */
public record ReviewMemory(int schemaVersion, long memoryVersion, Purpose purpose, Status status,
                           CompletionCause completionCause, RevisionKey revisionKey, Instant sealedAt,
                           Long baseReviewRunId, Long parentMemoryRunId, List<String> inspectedPaths,
                           ReviewContent review, ClarificationContent clarification, String insufficientReason) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_FINDINGS = 50;
    public static final int MAX_PATHS = 100;
    public static final int MAX_QUESTIONS = 20;
    public static final int MAX_SERIALIZED_CHARS = 24_000;
    public static final int MAX_SUMMARY = 4_000;
    public static final int MAX_QUESTION = 8_000;
    public static final int MAX_ANSWER = 16_000;
    public static final int MAX_TITLE = 256;
    public static final int MAX_DESCRIPTION = 2_000;
    public static final int MAX_RECOMMENDATION = 2_000;
    public static final int MAX_PATH = 1_024;
    public static final int MAX_OPEN_QUESTION = 1_000;

    /** Purpose is fixed by the application at admission. */
    public enum Purpose { REVIEW, CLARIFICATION }
    /** A terminal snapshot can never be replaced. */
    public enum Status { DRAFT, COMPLETE, INSUFFICIENT_CONTEXT }
    /** Fixed by the application, including across finalization and repair. */
    public enum CompletionCause { VOLUNTARY, FORCED }
    /** Confirmed finding severity; there is deliberately no model-defined extension. */
    public enum Severity { BLOCKER, MEDIUM, LOW }

    public ReviewMemory {
        MemoryChecks.require(schemaVersion == SCHEMA_VERSION, "Unsupported review memory schemaVersion");
        MemoryChecks.require(memoryVersion > 0, "memoryVersion must be positive");
        MemoryChecks.require(purpose != null && status != null && revisionKey != null, "Missing application metadata");
        MemoryChecks.runId(baseReviewRunId);
        MemoryChecks.runId(parentMemoryRunId);
        MemoryChecks.require(purpose != Purpose.REVIEW || (baseReviewRunId == null && parentMemoryRunId == null),
                "Review cannot carry clarification lineage");
        inspectedPaths = MemoryChecks.copy(inspectedPaths, MAX_PATHS, "inspectedPaths");
        inspectedPaths.forEach(MemoryChecks::path);
        inspectedPaths = List.copyOf(new TreeSet<>(inspectedPaths));
        MemoryChecks.require(purpose == Purpose.REVIEW ? review != null && clarification == null
                : clarification != null && review == null, "Content must match purpose exclusively");
        if (status == Status.DRAFT) {
            MemoryChecks.require(completionCause == null && sealedAt == null && insufficientReason == null,
                    "DRAFT cannot carry terminal metadata");
        } else {
            MemoryChecks.require(completionCause != null && sealedAt != null, "Terminal metadata is required");
            if (status == Status.INSUFFICIENT_CONTEXT) {
                MemoryChecks.text(insufficientReason, 4_000, true, "insufficientReason");
            } else {
                MemoryChecks.require(insufficientReason == null, "COMPLETE cannot carry insufficientReason");
                if (purpose == Purpose.REVIEW) {
                    MemoryChecks.text(review.summary(), MAX_SUMMARY, true, "summary");
                    MemoryChecks.require(review.openQuestions().isEmpty(), "COMPLETE review has open questions");
                } else {
                    MemoryChecks.text(clarification.answer(), MAX_ANSWER, true, "answer");
                }
            }
        }
    }

    /** Confirmed review content; candidates belong in openQuestions, not findings. */
    public record ReviewContent(String summary, List<String> openQuestions, List<Finding> findings) {
        public ReviewContent {
            MemoryChecks.text(summary, MAX_SUMMARY, false, "summary");
            openQuestions = MemoryChecks.copy(openQuestions, MAX_QUESTIONS, "openQuestions");
            openQuestions.forEach(q -> MemoryChecks.text(q, MAX_OPEN_QUESTION, true, "openQuestion"));
            findings = MemoryChecks.copy(findings, MAX_FINDINGS, "findings");
            var ids = new HashSet<String>();
            findings.forEach(f -> MemoryChecks.require(ids.add(f.id()), "Duplicate finding id"));
        }
    }

    /** The question is copied from the application trigger and never replaced by model content. */
    public record ClarificationContent(String question, String answer) {
        public ClarificationContent {
            MemoryChecks.text(question, MAX_QUESTION, true, "question");
            MemoryChecks.text(answer, MAX_ANSWER, false, "answer");
        }
    }

    /** A stable application-assigned identity and bounded, confirmed evidence; unknown location is omitted. */
    public record Finding(String id, Severity severity, String category, String title, String description,
                          String recommendation, String file, Integer line, String cwe, String owasp) {
        public Finding {
            MemoryChecks.text(id, 128, true, "finding id");
            MemoryChecks.finding(severity, category, title, description, recommendation, file, line, cwe, owasp);
        }
    }
}
