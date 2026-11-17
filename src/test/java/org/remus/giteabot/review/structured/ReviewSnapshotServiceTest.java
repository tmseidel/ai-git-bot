package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.config.LearningProperties;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshots are supplemental evidence: bounded, explicit about what is missing, and never a rewrite
 * of an earlier capture.
 */
@ExtendWith(MockitoExtension.class)
class ReviewSnapshotServiceTest {

    @Mock private WorkflowOutputSnapshotRepository snapshotRepository;
    @Mock private WorkflowOutputRepository outputRepository;
    @Mock private ReviewRecordRepository reviewRepository;

    private ReviewSnapshotService service;
    private LearningProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LearningProperties();
        properties.setSnapshotMaxChars(2_000);
        service = new ReviewSnapshotService(snapshotRepository, outputRepository, reviewRepository,
                properties);
        WorkflowOutput output = new WorkflowOutput();
        output.setId(10L);
        output.setReference("O1234");
        ReviewRecord record = new ReviewRecord();
        record.setId(1L);
        record.setOutput(output);
        lenient().when(outputRepository.findByReference("O1234")).thenReturn(Optional.of(output));
        lenient().when(reviewRepository.findByOutputId(10L)).thenReturn(Optional.of(record));
        lenient().when(snapshotRepository.findByReviewId(1L)).thenReturn(Optional.empty());
        lenient().when(snapshotRepository.save(any(WorkflowOutputSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void capturesACompleteSnapshotWhenTheInputsAreKnown() {
        WorkflowOutputSnapshot snapshot = service.capture(new ReviewSnapshotRequest("O1234",
                "Update example", "Body", "diff --git a b", "SYSTEM PROMPT", "GITEA",
                "model-x", 7L, "feedbackEnabled=true", "base-sha", "head-sha", "main", "feature",
                "repo-42", List.of()));

        assertThat(snapshot.isComplete()).isTrue();
        assertThat(snapshot.getIncompleteReason()).isNull();
        assertThat(snapshot.getDiffText()).isEqualTo("diff --git a b");
        assertThat(snapshot.getSystemPromptHash()).hasSize(64);
        assertThat(snapshot.getSettingsHash()).isEqualTo(Digests.sha256Hex("feedbackEnabled=true"));
        assertThat(snapshot.getWorkflowConfigurationId()).isEqualTo(7L);
    }

    @Test
    void marksMissingInputsAndTruncatesOversizedContent() {
        String oversized = "x".repeat(5_000);

        WorkflowOutputSnapshot snapshot = service.capture(new ReviewSnapshotRequest("O1234",
                "Title", null, oversized, oversized, "GITEA", null, null, null, null, null, null, null,
                null, List.of("the pull request head moved while the review ran")));

        assertThat(snapshot.isComplete()).isFalse();
        assertThat(snapshot.getIncompleteReason())
                .contains("the pull request head moved while the review ran")
                .contains("no pinned head revision")
                .contains("no pinned base revision")
                .contains("the AI model could not be identified")
                .contains("the provider repository identity is unknown")
                .contains("the diff was truncated to the snapshot limit")
                .contains("the system prompt was truncated to the snapshot limit");
        assertThat(snapshot.getDiffText()).hasSize(1_000);
        assertThat(snapshot.getSystemPrompt()).hasSize(1_000);
        assertThat(snapshot.getSettingsHash()).isNull();
    }

    @Test
    void anExistingSnapshotIsNotRewritten() {
        WorkflowOutputSnapshot existing = new WorkflowOutputSnapshot();
        existing.setId(9L);
        when(snapshotRepository.findByReviewId(1L)).thenReturn(Optional.of(existing));

        WorkflowOutputSnapshot snapshot = service.capture(new ReviewSnapshotRequest("O1234", "T", "B",
                "d", "p", "GITEA", "m", 1L, "f", "b", "h", "main", "feature", "r", List.of()));

        assertThat(snapshot).isSameAs(existing);
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void purgeRemovesOnlySnapshotsPastTheRetention() {
        properties.setSnapshotRetentionDays(30);
        WorkflowOutputSnapshot old = new WorkflowOutputSnapshot();
        when(snapshotRepository.findByCreatedAtBefore(any(Instant.class))).thenReturn(List.of(old));

        assertThat(service.purgeExpired()).isEqualTo(1);
        verify(snapshotRepository).deleteAll(List.of(old));
    }
}
