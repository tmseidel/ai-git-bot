package org.remus.giteabot.prworkflow.agentreview.memory;

import java.util.List;

/** Model-owned inputs, deliberately separate from persisted application metadata. */
public final class ReviewMemoryInputs {
    private ReviewMemoryInputs() { }

    /** Only terminal results; the model cannot set the snapshot's lifecycle status. */
    public enum Result { COMPLETE, INSUFFICIENT_CONTEXT }

    /** existingId references a current finding; null means a new, not-yet-identified finding. */
    public record FindingInput(String existingId, ReviewMemory.Severity severity, String category, String title,
                               String description, String recommendation, String file, Integer line, String cwe, String owasp) {
        public FindingInput {
            if (existingId != null) {
                MemoryChecks.text(existingId, 128, true, "existingId");
            }
            MemoryChecks.finding(severity, category, title, description, recommendation, file, line, cwe, owasp);
        }
    }

    /** Complete replacement content, not a patch or an additional set of findings. */
    public record ReviewContentInput(String summary, List<String> openQuestions, List<FindingInput> findings) {
        public ReviewContentInput {
            MemoryChecks.text(summary, ReviewMemory.MAX_SUMMARY, false, "summary");
            openQuestions = MemoryChecks.copy(openQuestions, ReviewMemory.MAX_QUESTIONS, "openQuestions");
            openQuestions.forEach(q -> MemoryChecks.text(q, ReviewMemory.MAX_OPEN_QUESTION, true, "openQuestion"));
            findings = MemoryChecks.copy(findings, ReviewMemory.MAX_FINDINGS, "findings");
        }
    }

    /** Explicit explanation for omitting an existing finding from a full replacement. */
    public record Removal(String id, String reason) {
        public Removal {
            MemoryChecks.text(id, 128, true, "removal id");
            MemoryChecks.text(reason, 2_000, true, "removal reason");
        }
    }

    /** Nonterminal review input; contains no purpose, lifecycle, evidence coverage or revision. */
    public record UpdateReviewMemoryInput(ReviewContentInput review, List<Removal> removals) {
        public UpdateReviewMemoryInput {
            MemoryChecks.require(review != null, "Missing review content");
            removals = MemoryChecks.copy(removals, ReviewMemory.MAX_FINDINGS, "removals");
        }
    }

    /** Replaces only the clarification answer draft; the trigger question is application-owned. */
    public record UpdateClarificationMemoryInput(String answerDraft) {
        public UpdateClarificationMemoryInput {
            MemoryChecks.text(answerDraft, ReviewMemory.MAX_ANSWER, false, "answerDraft");
        }
    }

    /** Strict terminal union: full content plus removals, or only an insufficient-context reason. */
    public record SubmitReviewInput(Result result, ReviewContentInput review, List<Removal> removals, String insufficientReason) {
        public SubmitReviewInput {
            MemoryChecks.require(result != null, "Missing result");
            removals = MemoryChecks.copy(removals, ReviewMemory.MAX_FINDINGS, "removals");
            if (result == Result.COMPLETE) {
                MemoryChecks.require(review != null && insufficientReason == null, "Invalid COMPLETE review input");
                MemoryChecks.text(review.summary(), ReviewMemory.MAX_SUMMARY, true, "summary");
                MemoryChecks.require(review.openQuestions().isEmpty(), "COMPLETE review has open questions");
            } else {
                MemoryChecks.require(review == null && removals.isEmpty(), "Insufficient submit cannot replace content");
                MemoryChecks.text(insufficientReason, 4_000, true, "insufficientReason");
            }
        }
    }

    /** Terminal answer or insufficient reason; cannot carry review content, question or a formal action. */
    public record SubmitClarificationInput(Result result, String answer, String insufficientReason) {
        public SubmitClarificationInput {
            MemoryChecks.require(result != null, "Missing result");
            if (result == Result.COMPLETE) {
                MemoryChecks.require(insufficientReason == null, "COMPLETE cannot carry insufficientReason");
                MemoryChecks.text(answer, ReviewMemory.MAX_ANSWER, true, "answer");
            } else {
                MemoryChecks.require(answer == null, "Insufficient submit cannot replace content");
                MemoryChecks.text(insufficientReason, 4_000, true, "insufficientReason");
            }
        }
    }
}
