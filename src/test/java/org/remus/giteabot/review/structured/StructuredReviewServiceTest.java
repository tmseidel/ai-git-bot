package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredReviewServiceTest {

    @Mock private WorkflowOutputRepository outputRepository;
    @Mock private ReviewRecordRepository reviewRepository;
    @Mock private ReferenceAllocator referenceAllocator;

    private StructuredReviewService service;
    private ReviewRecord review;

    @BeforeEach
    void setUp() {
        service = new StructuredReviewService(outputRepository, reviewRepository, referenceAllocator);
        review = draftReview();
        lenient().when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
        lenient().when(outputRepository.save(any(WorkflowOutput.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(reviewRepository.save(any(ReviewRecord.class))).thenAnswer(invocation -> {
            ReviewRecord saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1L);
            }
            return saved;
        });
        lenient().when(referenceAllocator.next(ReferenceNamespace.OUTPUT)).thenReturn("O1234");
    }

    @Test
    void createDraftReservesReferenceAndPersistsScope() {
        ReviewDraftHandle handle = service.createDraft(new ReviewDraftRequest(
                7L, 3L, "acme", "web", "repo-42", 9L, 55L, "inv-1", "base-sha", "head-sha"));

        ArgumentCaptor<WorkflowOutput> output = ArgumentCaptor.forClass(WorkflowOutput.class);
        verify(outputRepository).save(output.capture());
        assertEquals("O1234", output.getValue().getReference());
        assertEquals("agentic-review", output.getValue().getWorkflowKey());
        assertEquals("review", output.getValue().getStage());
        assertEquals(7L, output.getValue().getGitIntegrationId());
        assertEquals(3L, output.getValue().getBotId());
        assertEquals("repo-42", output.getValue().getProviderRepositoryId());
        assertEquals(9L, output.getValue().getPrNumber());
        assertEquals("inv-1", output.getValue().getInvocationId());

        assertEquals("O1234", handle.outputReference());
        assertEquals(0, handle.draftRevision());
        ArgumentCaptor<ReviewRecord> record = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRepository).save(record.capture());
        assertEquals(ReviewState.DRAFT, record.getValue().getState());
        assertEquals(StructuredReviewService.SCHEMA_VERSION, record.getValue().getSchemaVersion());
        assertEquals("base-sha", record.getValue().getBaseSha());
        assertEquals("head-sha", record.getValue().getHeadSha());
    }

    @Test
    void createDraftRejectsMissingScope() {
        InvalidReviewContentException failure = assertThrows(InvalidReviewContentException.class,
                () -> service.createDraft(new ReviewDraftRequest(
                        null, 3L, "acme", "web", null, 9L, null, "inv-1", null, null)));

        assertEquals("gitIntegrationId", failure.getField());
    }

    @Test
    void upsertFindingAssignsServerOrdinalAndReference() {
        FindingHandle handle = service.upsertFinding(1L, 0, null, finding("settings-auth",
                ReviewSeverity.MEDIUM, "Settings update may bypass authorization"));

        assertEquals(1, handle.ordinal());
        assertEquals("O1234-F1", handle.reference());
        assertEquals(1, handle.revision());
        assertEquals(1, review.getDraftRevision());
        assertEquals(1, review.getFindings().size());
        ReviewFinding stored = review.getFindings().get(0);
        assertEquals("settings-auth", stored.getClientKey());
        assertEquals(1, stored.getLocations().size());
        assertEquals("src/Foo.java", stored.getLocations().get(0).getPath());
        assertEquals(ReviewSide.HEAD, stored.getLocations().get(0).getSide());
    }

    @Test
    void upsertFindingKeepsOrdinalOnCorrection() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));

        FindingHandle corrected = service.upsertFinding(1L, 1, 1,
                finding("settings-auth", ReviewSeverity.BLOCKER, "Corrected title"));

        assertEquals(1, corrected.ordinal());
        assertEquals("O1234-F1", corrected.reference());
        assertEquals(2, corrected.revision());
        assertEquals(1, review.getFindings().size());
        assertEquals("Corrected title", review.getFindings().get(0).getTitle());
        assertEquals(ReviewSeverity.BLOCKER, review.getFindings().get(0).getSeverity());
    }

    @Test
    void upsertFindingRejectsStaleDraftRevision() {
        ReviewDraftConflictException failure = assertThrows(ReviewDraftConflictException.class,
                () -> service.upsertFinding(1L, 5, null,
                        finding("settings-auth", ReviewSeverity.LOW, "Title")));

        assertEquals(0, failure.getCurrentDraftRevision());
        assertTrue(review.getFindings().isEmpty());
    }

    @Test
    void upsertFindingRejectsStaleFindingRevision() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.LOW, "Title"));

        assertThrows(ReviewDraftConflictException.class,
                () -> service.upsertFinding(1L, 1, 9,
                        finding("settings-auth", ReviewSeverity.LOW, "Title")));
    }

    @Test
    void upsertFindingRejectsNonRelativeAndTraversalPaths() {
        assertInvalidField("path", new LocationDraft("/etc/passwd", ReviewSide.HEAD, 1, 2, "evidence"));
        assertInvalidField("path", new LocationDraft("../secrets.txt", ReviewSide.HEAD, 1, 2, "evidence"));
        assertInvalidField("path", new LocationDraft("src\\Windows.java", ReviewSide.HEAD, 1, 2, "evidence"));
    }

    @Test
    void upsertFindingRejectsInvalidLineRanges() {
        assertInvalidField("line", new LocationDraft("src/A.java", ReviewSide.HEAD, 5, 3, "evidence"));
        assertInvalidField("line", new LocationDraft("src/A.java", ReviewSide.HEAD, null, 3, "evidence"));
        assertInvalidField("line", new LocationDraft("src/A.java", ReviewSide.HEAD, 0, 3, "evidence"));
    }

    @Test
    void upsertFindingRejectsMissingEvidenceDescription() {
        assertInvalidField("evidence", new LocationDraft("src/A.java", ReviewSide.HEAD, 1, 2, "  "));
    }

    @Test
    void withdrawnFindingIsNotReusedAndItsClientKeyIsRejected() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.LOW, "First"));
        service.withdrawFinding(1L, 1, 1, 1, "Inspected the enclosing filter");

        ReviewDraftConflictException rejected = assertThrows(ReviewDraftConflictException.class,
                () -> service.upsertFinding(1L, 2, null,
                        finding("settings-auth", ReviewSeverity.LOW, "First")));
        assertTrue(rejected.getMessage().contains("withdrawn"));

        FindingHandle next = service.upsertFinding(1L, 2, null,
                finding("settings-other", ReviewSeverity.LOW, "Second"));
        assertEquals(2, next.ordinal());
        assertEquals("O1234-F2", next.reference());
        assertEquals(1, countActive());
        assertEquals(2, review.getFindings().size());
    }

    @Test
    void withdrawFindingRejectsStaleFindingRevision() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.LOW, "First"));

        assertThrows(ReviewDraftConflictException.class,
                () -> service.withdrawFinding(1L, 1, 7, 1, "reason"));
    }

    @Test
    void assessmentMustAgreeWithActiveFindings() {
        InvalidReviewContentException noFindings = assertThrows(InvalidReviewContentException.class,
                () -> service.setAssessment(1L, 0,
                        new AssessmentDraft("Summary.", ReviewConclusion.FINDINGS_PRESENT, null, null)));
        assertEquals("conclusion", noFindings.getField());

        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.LOW, "First"));

        assertThrows(InvalidReviewContentException.class, () -> service.setAssessment(1L, 1,
                new AssessmentDraft("Summary.", ReviewConclusion.NO_FINDINGS, null, null)));
    }

    @Test
    void inconclusiveAssessmentRequiresLimitations() {
        assertThrows(InvalidReviewContentException.class, () -> service.setAssessment(1L, 0,
                new AssessmentDraft("Summary.", ReviewConclusion.INCONCLUSIVE, null, null)));
    }

    @Test
    void findingEditInvalidatesAssessment() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));
        assertEquals(review.getDraftRevision(), review.getAssessment().getAssessedRevision());

        service.upsertFinding(1L, 2, 1, finding("settings-auth", ReviewSeverity.MEDIUM, "Corrected"));

        assertEquals(ReviewAssessment.STALE_REVISION, review.getAssessment().getAssessedRevision());
        DraftSnapshot snapshot = service.findDraft(1L).orElseThrow();
        assertFalse(snapshot.assessmentCurrent());
    }

    @Test
    void finalizeFreezesRecordWithCountsAndHash() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.upsertFinding(1L, 1, null, finding("naming", ReviewSeverity.LOW, "Second"));
        service.setAssessment(1L, 2, assessment(ReviewConclusion.FINDINGS_PRESENT));

        FinalizedReview finalized = service.finalizeReview(1L, 3, 2);

        assertEquals("O1234", finalized.reference());
        assertEquals("O1234-A", finalized.assessmentReference());
        assertEquals(0, finalized.counts().blocker());
        assertEquals(1, finalized.counts().medium());
        assertEquals(1, finalized.counts().low());
        assertEquals(List.of("O1234-F1", "O1234-F2"), finalized.findings().stream()
                .map(FinalizedReview.Finding::reference).toList());
        assertEquals(64, finalized.contentHash().length());
        assertEquals(ReviewState.FINALIZED, review.getState());
        assertNotNull(review.getFinalizedAt());
        assertEquals(finalized.contentHash(), review.getContentHash());
    }

    @Test
    void finalizeRequiresAssessment() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));

        InvalidReviewContentException failure = assertThrows(InvalidReviewContentException.class,
                () -> service.finalizeReview(1L, 1, 1));
        assertEquals("assessment", failure.getField());
    }

    @Test
    void finalizeRejectsStaleAssessment() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));
        service.upsertFinding(1L, 2, 1, finding("settings-auth", ReviewSeverity.MEDIUM, "Corrected"));

        InvalidReviewContentException failure = assertThrows(InvalidReviewContentException.class,
                () -> service.finalizeReview(1L, 3, 1));
        assertEquals("assessment", failure.getField());
    }

    @Test
    void finalizeRejectsFindingCountAndRevisionMismatch() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));

        assertThrows(ReviewDraftConflictException.class, () -> service.finalizeReview(1L, 2, 5));
        assertThrows(ReviewDraftConflictException.class, () -> service.finalizeReview(1L, 7, 1));
    }

    @Test
    void finalizeIsRejectedTwice() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));
        service.finalizeReview(1L, 2, 1);

        assertThrows(ReviewDraftConflictException.class, () -> service.finalizeReview(1L, 2, 1));
    }

    @Test
    void finalizedReviewRejectsFurtherEdits() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));
        service.finalizeReview(1L, 2, 1);

        assertThrows(ReviewDraftConflictException.class, () -> service.upsertFinding(1L, 2, 1,
                finding("settings-auth", ReviewSeverity.MEDIUM, "Corrected")));
    }

    @Test
    void zeroFindingsCanBeFinalizedWithAnExplicitAssessment() {
        service.setAssessment(1L, 0, assessment(ReviewConclusion.NO_FINDINGS));

        FinalizedReview finalized = service.finalizeReview(1L, 1, 0);

        assertTrue(finalized.findings().isEmpty());
        assertEquals(ReviewConclusion.NO_FINDINGS, finalized.conclusion());
        assertEquals(0, finalized.counts().blocker() + finalized.counts().medium()
                + finalized.counts().low());
    }

    @Test
    void contentHashTracksContentAndNotDisplayOrder() {
        FinalizedReview first = new FinalizedReview("O1234", "O1234-A",
                ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null, null,
                new FinalizedReview.SeverityCounts(0, 1, 0),
                List.of(finalizedFinding("O1234-F1", "Title")), null);
        FinalizedReview same = new FinalizedReview("O1234", "O1234-A",
                ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null, null,
                new FinalizedReview.SeverityCounts(0, 1, 0),
                List.of(finalizedFinding("O1234-F1", "Title")), null);
        FinalizedReview changed = new FinalizedReview("O1234", "O1234-A",
                ReviewConclusion.FINDINGS_PRESENT, "Summary.", null, null, null,
                new FinalizedReview.SeverityCounts(0, 1, 0),
                List.of(finalizedFinding("O1234-F1", "Other title")), null);

        assertEquals(StructuredReviewService.hash(first), StructuredReviewService.hash(same));
        assertFalse(StructuredReviewService.hash(first)
                .equals(StructuredReviewService.hash(changed)));
    }

    @Test
    void findFinalizedByReferenceOnlyReturnsFinalizedRecords() {
        WorkflowOutput output = new WorkflowOutput();
        output.setId(10L);
        output.setReference("O1234");
        when(outputRepository.findByReference("O1234")).thenReturn(Optional.of(output));
        when(reviewRepository.findByOutputId(10L)).thenReturn(Optional.of(review));

        assertTrue(service.findFinalizedByReference("O1234").isEmpty());

        review.setState(ReviewState.FINALIZED);
        assertTrue(service.findFinalizedByReference("O1234").isPresent());
    }

    @Test
    void findFinalizedReturnsEmptyForDraftAndUnknownReviews() {
        assertTrue(service.findFinalized(1L).isEmpty());
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.findFinalized(99L).isEmpty());
    }

    @Test
    void draftSnapshotReportsCurrentAssessment() {
        service.upsertFinding(1L, 0, null, finding("settings-auth", ReviewSeverity.MEDIUM, "First"));
        service.setAssessment(1L, 1, assessment(ReviewConclusion.FINDINGS_PRESENT));

        DraftSnapshot snapshot = service.findDraft(1L).orElseThrow();

        assertEquals("O1234", snapshot.outputReference());
        assertEquals(ReviewState.DRAFT, snapshot.state());
        assertEquals(2, snapshot.draftRevision());
        assertEquals(1, snapshot.findings().size());
        assertEquals("O1234-F1", snapshot.findings().get(0).reference());
        assertTrue(snapshot.assessmentCurrent());
    }

    private void assertInvalidField(String field, LocationDraft location) {
        InvalidReviewContentException failure = assertThrows(InvalidReviewContentException.class,
                () -> service.upsertFinding(1L, review.getDraftRevision(), null,
                        new FindingDraft("key", ReviewSeverity.LOW, "category", "Title",
                                "Explanation.", "Impact.", "Recommendation.", List.of(location))));
        assertEquals(field, failure.getField());
    }

    private int countActive() {
        return (int) review.getFindings().stream().filter(finding -> !finding.isWithdrawn()).count();
    }

    private static ReviewRecord draftReview() {
        WorkflowOutput output = new WorkflowOutput();
        output.setId(10L);
        output.setReference("O1234");
        ReviewRecord record = new ReviewRecord();
        record.setId(1L);
        record.setOutput(output);
        record.setState(ReviewState.DRAFT);
        record.setSchemaVersion(StructuredReviewService.SCHEMA_VERSION);
        record.setDraftRevision(0);
        return record;
    }

    private static FindingDraft finding(String clientKey, ReviewSeverity severity, String title) {
        return new FindingDraft(clientKey, severity, "authorization", title,
                "The observed update path does not show the required permission check.",
                "An unauthorized caller could change settings.",
                "Verify the enclosing filter.",
                List.of(new LocationDraft("src/Foo.java", ReviewSide.HEAD, 10, 20, "Evidence text.")));
    }

    private static AssessmentDraft assessment(ReviewConclusion conclusion) {
        return new AssessmentDraft("Summary text.", conclusion, "Reviewed text.",
                conclusion == ReviewConclusion.INCONCLUSIVE ? "Limitations text." : null);
    }

    private static FinalizedReview.Finding finalizedFinding(String reference, String title) {
        return new FinalizedReview.Finding(reference, ReviewSeverity.MEDIUM, "authorization", title,
                "Explanation.", "Impact.", "Recommendation.",
                List.of(new FinalizedReview.Location("src/Foo.java", ReviewSide.HEAD, 10, 20,
                        "Evidence text.")));
    }
}
