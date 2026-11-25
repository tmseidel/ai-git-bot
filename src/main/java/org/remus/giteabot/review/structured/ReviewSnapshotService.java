package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.LearningProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded capture of the evidence a review was produced from, for later feedback analysis.
 *
 * <p>One row per review; content beyond the configured budget is dropped and the row records why it
 * is incomplete, so a later analysis can tell "the model saw this" from "this is all that was
 * kept". Capturing is optional and never blocks the review itself.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSnapshotService {

    private final WorkflowOutputSnapshotRepository snapshotRepository;
    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;
    private final LearningProperties properties;

    /**
     * Stores the snapshot for {@code request.outputReference()}. A second call for the same review
     * is a no-op: a rerun creates a new review, not a rewritten snapshot.
     */
    @Transactional
    public WorkflowOutputSnapshot capture(ReviewSnapshotRequest request) {
        WorkflowOutput output = outputRepository.findByReference(request.outputReference())
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + request.outputReference()));
        ReviewRecord review = reviewRepository.findByOutputId(output.getId())
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Review " + request.outputReference() + " has no record"));

        WorkflowOutputSnapshot existing = snapshotRepository.findByReviewId(review.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        int budget = Math.max(1_024, properties.getSnapshotMaxChars());
        List<String> reasons = new ArrayList<>(request.incompleteReasons());
        if (isBlank(request.headSha())) {
            reasons.add("no pinned head revision");
        }
        if (isBlank(request.baseSha())) {
            reasons.add("no pinned base revision");
        }
        if (isBlank(request.model())) {
            reasons.add("the AI model could not be identified");
        }
        if (isBlank(request.providerRepositoryId())) {
            reasons.add("the provider repository identity is unknown");
        }

        Bounded diff = bound(request.diff(), budget / 2, "the diff was truncated to the snapshot limit",
                reasons);
        Bounded prompt = bound(request.systemPrompt(), budget / 2,
                "the system prompt was truncated to the snapshot limit", reasons);

        WorkflowOutputSnapshot snapshot = new WorkflowOutputSnapshot();
        snapshot.setReviewId(review.getId());
        snapshot.setOutputId(output.getId());
        snapshot.setPrTitle(request.prTitle());
        snapshot.setPrBody(request.prBody());
        snapshot.setDiffText(diff.text());
        snapshot.setSystemPrompt(prompt.text());
        snapshot.setSystemPromptHash(prompt.text() == null ? null : Digests.sha256Hex(prompt.text()));
        snapshot.setBaseSha(request.baseSha());
        snapshot.setHeadSha(request.headSha());
        snapshot.setBaseRef(request.baseRef());
        snapshot.setHeadRef(request.headRef());
        snapshot.setProviderRepositoryId(request.providerRepositoryId());
        snapshot.setProvider(request.provider());
        snapshot.setModel(request.model());
        snapshot.setWorkflowConfigurationId(request.workflowConfigurationId());
        snapshot.setSettingsHash(request.settingsFingerprint() == null ? null
                : Digests.sha256Hex(request.settingsFingerprint()));
        snapshot.setLessonSelection(request.lessonSelection());
        String lessons = request.lessonRevisions();
        if (lessons != null && lessons.length() > 1_000) {
            reasons.add("the list of supplied lesson revisions was truncated");
            lessons = lessons.substring(0, 1_000);
        }
        snapshot.setLessonRevisions(lessons);
        snapshot.setLessonContextHash(request.lessonContextHash());
        snapshot.setComplete(reasons.isEmpty());
        snapshot.setIncompleteReason(reasons.isEmpty() ? null : String.join("; ", reasons));
        if (!reasons.isEmpty()) {
            log.info("Snapshot of review {} is incomplete: {}", request.outputReference(),
                    snapshot.getIncompleteReason());
        }
        return snapshotRepository.save(snapshot);
    }

    /** Deletes snapshots older than the configured retention; reviews and findings stay. */
    @Transactional
    public int purgeExpired() {
        int days = Math.max(1, properties.getSnapshotRetentionDays());
        List<WorkflowOutputSnapshot> expired =
                snapshotRepository.findByCreatedAtBefore(Instant.now().minusSeconds(days * 86_400L));
        snapshotRepository.deleteAll(expired);
        return expired.size();
    }

    private static Bounded bound(String value, int limit, String reason, List<String> reasons) {
        if (value == null) {
            return new Bounded(null);
        }
        if (value.length() <= limit) {
            return new Bounded(value);
        }
        reasons.add(reason);
        return new Bounded(value.substring(0, limit));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Bounded(String text) {
    }
}
