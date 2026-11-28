package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.review.structured.ReferenceAllocator;
import org.remus.giteabot.review.structured.ReferenceNamespace;
import org.remus.giteabot.review.structured.ReviewAssessment;
import org.remus.giteabot.review.structured.ReviewFinding;
import org.remus.giteabot.review.structured.ReviewRecord;
import org.remus.giteabot.review.structured.ReviewRecordRepository;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.WorkflowOutput;
import org.remus.giteabot.review.structured.WorkflowOutputRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final FeedbackScope SCOPE = new FeedbackScope(1L, 2L, "acme", "web", 9L);

    @Mock private WorkflowFeedbackRepository feedbackRepository;
    @Mock private WorkflowFeedbackEventRepository eventRepository;
    @Mock private WorkflowOutputRepository outputRepository;
    @Mock private ReviewRecordRepository reviewRepository;
    @Mock private ReferenceAllocator referenceAllocator;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FeedbackService service;
    private ReviewRecord review;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, eventRepository, outputRepository,
                reviewRepository, referenceAllocator, transactionManager, eventPublisher);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        WorkflowOutput output = new WorkflowOutput();
        output.setId(10L);
        output.setReference("O1234");
        output.setGitIntegrationId(1L);
        output.setBotId(2L);
        output.setRepoOwner("acme");
        output.setRepoName("web");
        output.setPrNumber(9L);
        output.setWorkflowKey("agentic-review");
        output.setStage("review");

        review = new ReviewRecord();
        review.setId(20L);
        review.setOutput(output);
        review.setState(ReviewState.FINALIZED);
        ReviewAssessment assessment = new ReviewAssessment();
        assessment.setReview(review);
        review.setAssessment(assessment);
        review.getFindings().add(finding(2, false));

        lenient().when(outputRepository.findByReference(anyString())).thenReturn(Optional.of(output));
        lenient().when(reviewRepository.findByOutputId(10L)).thenReturn(Optional.of(review));
        lenient().when(referenceAllocator.next(ReferenceNamespace.FEEDBACK)).thenReturn("FB7");
        lenient().when(feedbackRepository.findEffective(anyLong(), any(), any(), anyString()))
                .thenReturn(java.util.List.of());
        lenient().when(feedbackRepository.saveAndFlush(any(WorkflowFeedback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(eventRepository.save(any(WorkflowFeedbackEvent.class)))
                .thenAnswer(invocation -> {
                    WorkflowFeedbackEvent event = invocation.getArgument(0);
                    event.setId(50L);
                    return event;
                });
    }

    @Test
    void recordsAnAssessmentWithItsTypedTarget() {
        FeedbackService.Recorded recorded = service.submit(SCOPE, source(), FeedbackTarget.finding("O1234", 2),
                FeedbackVerdict.INCORRECT, "  Authorization is enforced already.  ", "alice");

        assertThat(recorded.reference()).isEqualTo("FB7");
        assertThat(recorded.targetReference()).isEqualTo("O1234-F2");
        assertThat(recorded.duplicate()).isFalse();

        ArgumentCaptor<WorkflowFeedback> saved = ArgumentCaptor.forClass(WorkflowFeedback.class);
        verify(feedbackRepository).saveAndFlush(saved.capture());
        WorkflowFeedback record = saved.getValue();
        assertThat(record.getTargetType()).isEqualTo(FeedbackTargetType.FINDING);
        assertThat(record.getFindingId()).isEqualTo(30L);
        assertThat(record.getVerdict()).isEqualTo(FeedbackVerdict.INCORRECT);
        assertThat(record.getReason()).isEqualTo("Authorization is enforced already.");
        assertThat(record.getActor()).isEqualTo("alice");
        assertThat(record.getState()).isEqualTo(FeedbackState.EFFECTIVE);
        assertThat(record.getSourceKey()).isEqualTo(source().sourceKey());
        assertThat(record.getPrNumber()).isEqualTo(9L);
    }

    @Test
    void anAssessmentAndTheWholeReviewAreDistinctTargets() {
        service.submit(SCOPE, source(), FeedbackTarget.assessment("O1234"),
                FeedbackVerdict.NEEDS_IMPROVEMENT, "Please explain the unreviewed areas.", "alice");

        ArgumentCaptor<WorkflowFeedback> saved = ArgumentCaptor.forClass(WorkflowFeedback.class);
        verify(feedbackRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTargetType()).isEqualTo(FeedbackTargetType.ASSESSMENT);
        assertThat(saved.getValue().getFindingId()).isNull();
        assertThat(saved.getValue().getTargetReference()).isEqualTo("O1234-A");
    }

    @Test
    void aReferenceFromAnotherScopeIsUnknownHere() {
        WorkflowOutput foreign = new WorkflowOutput();
        foreign.setId(11L);
        foreign.setReference("O1234");
        foreign.setGitIntegrationId(1L);
        foreign.setBotId(2L);
        foreign.setRepoOwner("other-org");
        foreign.setRepoName("web");
        foreign.setPrNumber(9L);
        foreign.setWorkflowKey("agentic-review");
        when(outputRepository.findByReference("O1234")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.submit(SCOPE, source(), FeedbackTarget.output("O1234"),
                FeedbackVerdict.USEFUL, "", "alice"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("unknown-target"));
        verify(feedbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlyFinalizedReviewsCanBeJudged() {
        review.setState(ReviewState.DRAFT);

        assertThatThrownBy(() -> service.submit(SCOPE, source(), FeedbackTarget.output("O1234"),
                FeedbackVerdict.USEFUL, "", "alice"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("not-finalized"));
    }

    @Test
    void aWithdrawnFindingCannotBeJudged() {
        review.getFindings().clear();
        review.getFindings().add(finding(2, true));

        assertThatThrownBy(() -> service.submit(SCOPE, source(), FeedbackTarget.finding("O1234", 2),
                FeedbackVerdict.INCORRECT, "", "alice"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("withdrawn-finding"));
    }

    @Test
    void anUnknownFindingCannotBeJudged() {
        assertThatThrownBy(() -> service.submit(SCOPE, source(), FeedbackTarget.finding("O1234", 9),
                FeedbackVerdict.INCORRECT, "", "alice"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("unknown-finding"));
    }

    @Test
    void redeliveryOfTheSameCommentRecordsNothingNew() {
        WorkflowFeedback existing = new WorkflowFeedback();
        existing.setReference("FB3");
        existing.setTargetReference("O1234-F2");
        existing.setTargetType(FeedbackTargetType.FINDING);
        existing.setVerdict(FeedbackVerdict.INCORRECT);
        existing.setActor("alice");
        when(feedbackRepository.findBySourceKey(source().sourceKey()))
                .thenReturn(Optional.of(existing));

        FeedbackService.Recorded recorded = service.submit(SCOPE, source(),
                FeedbackTarget.finding("O1234", 2), FeedbackVerdict.INCORRECT, "", "alice");

        assertThat(recorded.reference()).isEqualTo("FB3");
        assertThat(recorded.duplicate()).isTrue();
        verify(feedbackRepository, never()).saveAndFlush(any());
    }

    @Test
    void aCommentWithoutIdentityIsRejectedRatherThanRecorded() {
        FeedbackSource anonymous = new FeedbackSource(1L, 2L, "acme", "web", null,
                FeedbackSource.SubjectKind.PR, 9L, "PR_COMMENT", null);

        assertThatThrownBy(() -> service.submit(SCOPE, anonymous, FeedbackTarget.output("O1234"),
                FeedbackVerdict.USEFUL, "", "alice"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("unidentifiable-source"));
    }

    @Test
    void aNewCommentSupersedesTheSameActorsEffectiveRecord() {
        WorkflowFeedback previous = new WorkflowFeedback();
        previous.setReference("FB4");
        previous.setState(FeedbackState.EFFECTIVE);
        when(feedbackRepository.findEffective(10L, FeedbackTargetType.FINDING, 30L, "alice"))
                .thenReturn(java.util.List.of(previous));

        service.submit(SCOPE, source(), FeedbackTarget.finding("O1234", 2),
                FeedbackVerdict.USEFUL, "Actually it is fine.", "alice");

        assertThat(previous.getState()).isEqualTo(FeedbackState.SUPERSEDED);
        assertThat(previous.getSupersededBy()).isEqualTo("FB7");
        // Lessons citing FB4 must learn that its author changed their mind.
        verify(eventPublisher).publishEvent(new FeedbackWithdrawnEvent(null, "FB4",
                FeedbackState.SUPERSEDED, "FB7", "alice"));
    }

    @Test
    void onlyTheAuthorCanRetract() {
        WorkflowFeedback record = new WorkflowFeedback();
        record.setReference("FB9");
        record.setGitIntegrationId(1L);
        record.setBotId(2L);
        record.setRepoOwner("acme");
        record.setRepoName("web");
        record.setActor("alice");
        record.setState(FeedbackState.EFFECTIVE);
        record.setTargetReference("O1234");
        record.setTargetType(FeedbackTargetType.OUTPUT);
        record.setVerdict(FeedbackVerdict.USEFUL);
        when(feedbackRepository.findByReference("FB9")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.retract(SCOPE, "FB9", "mallory"))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("not-author"));
        assertThat(record.getState()).isEqualTo(FeedbackState.EFFECTIVE);

        assertThat(service.retract(SCOPE, "FB9", "ALICE").reference()).isEqualTo("FB9");
        assertThat(record.getState()).isEqualTo(FeedbackState.RETRACTED);
        verify(eventPublisher).publishEvent(new FeedbackWithdrawnEvent(null, "FB9",
                FeedbackState.RETRACTED, null, "ALICE"));
    }

    @Test
    void aLearningMaintainerMayRetractSomeoneElsesFeedback() {
        WorkflowFeedback record = new WorkflowFeedback();
        record.setReference("FB9");
        record.setGitIntegrationId(1L);
        record.setBotId(2L);
        record.setRepoOwner("acme");
        record.setRepoName("web");
        record.setActor("alice");
        record.setState(FeedbackState.EFFECTIVE);
        record.setTargetReference("O1234");
        record.setTargetType(FeedbackTargetType.OUTPUT);
        record.setVerdict(FeedbackVerdict.USEFUL);
        when(feedbackRepository.findByReference("FB9")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.retract(SCOPE, "FB9", "maria", false))
                .isInstanceOf(FeedbackRejectedException.class);
        assertThat(service.retract(SCOPE, "FB9", "maria", true).reference()).isEqualTo("FB9");
        assertThat(record.getState()).isEqualTo(FeedbackState.RETRACTED);
    }

    @Test
    void aRetractedRecordIsNotRetractableTwice() {
        WorkflowFeedback record = new WorkflowFeedback();
        record.setReference("FB9");
        record.setGitIntegrationId(1L);
        record.setBotId(2L);
        record.setRepoOwner("acme");
        record.setRepoName("web");
        record.setActor("alice");
        record.setState(FeedbackState.RETRACTED);
        record.setTargetReference("O1234");
        record.setTargetType(FeedbackTargetType.OUTPUT);
        record.setVerdict(FeedbackVerdict.USEFUL);
        when(feedbackRepository.findByReference("FB9")).thenReturn(Optional.of(record));

        assertThat(service.retract(SCOPE, "FB9", "alice").duplicate()).isTrue();
    }

    @Test
    void aReasonIsBoundedToTheColumn() {
        service.submit(SCOPE, source(), FeedbackTarget.output("O1234"), FeedbackVerdict.INCORRECT,
                "y".repeat(9_000), "alice");

        ArgumentCaptor<WorkflowFeedback> saved = ArgumentCaptor.forClass(WorkflowFeedback.class);
        verify(feedbackRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReason()).hasSize(FeedbackCommandParser.MAX_REASON_CHARS);
    }

    @Test
    void listingUsesTheRequestedScopeOnly() {
        when(feedbackRepository.pageForRepository(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), any())).thenReturn(java.util.List.of());

        service.list(SCOPE, true, 100, null);

        verify(feedbackRepository).pageForRepository(1L, 2L, "acme", "web", Long.MAX_VALUE,
                org.springframework.data.domain.PageRequest.of(0, FeedbackService.MAX_PAGE_SIZE + 1));
        verify(feedbackRepository, never()).pageForPullRequest(anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void showRejectsRecordsFromAnotherRepository() {
        WorkflowFeedback record = new WorkflowFeedback();
        record.setReference("FB9");
        record.setGitIntegrationId(1L);
        record.setBotId(2L);
        record.setRepoOwner("other-org");
        record.setRepoName("web");
        when(feedbackRepository.findByReference("FB9")).thenReturn(Optional.of(record));

        assertThat(service.show(SCOPE, "FB9")).isEmpty();
    }

    @Test
    void losingTheRaceToAParallelDeliveryAnswersWithTheWinnersRecord() {
        WorkflowFeedback winner = new WorkflowFeedback();
        winner.setReference("FB6");
        winner.setTargetReference("O1234");
        winner.setTargetType(FeedbackTargetType.OUTPUT);
        winner.setVerdict(FeedbackVerdict.USEFUL);
        winner.setActor("alice");
        when(feedbackRepository.findBySourceKey(source().sourceKey()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(feedbackRepository.saveAndFlush(any(WorkflowFeedback.class)))
                .thenThrow(new DataIntegrityViolationException("uk_workflow_feedback_source_key"));

        FeedbackService.Recorded recorded = service.submit(SCOPE, source(),
                FeedbackTarget.output("O1234"), FeedbackVerdict.USEFUL, "", "alice");

        assertThat(recorded.duplicate()).isTrue();
        assertThat(recorded.reference()).isEqualTo("FB6");
        // The failed attempt is rolled back before the winner is read in a transaction of its own.
        verify(transactionManager).rollback(any());
        verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(any());
    }

    @Test
    void anIntegrityViolationThatIsNoDuplicateIsNotReportedAsOne() {
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("fk_feedback_output");
        when(feedbackRepository.findBySourceKey(anyString())).thenReturn(Optional.empty());
        when(feedbackRepository.saveAndFlush(any(WorkflowFeedback.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> service.submit(SCOPE, source(), FeedbackTarget.output("O1234"),
                FeedbackVerdict.USEFUL, "", "alice")).isSameAs(unrelated);
    }

    @Test
    void aSubmissionStartsItsHistoryWithAPendingConfirmation() {
        FeedbackService.Recorded recorded = service.submit(SCOPE, source(),
                FeedbackTarget.output("O1234"), FeedbackVerdict.USEFUL, "", "alice");

        ArgumentCaptor<WorkflowFeedbackEvent> event = ArgumentCaptor.forClass(WorkflowFeedbackEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(FeedbackEventType.SUBMITTED);
        assertThat(event.getValue().getActor()).isEqualTo("alice");
        assertThat(event.getValue().getAcknowledgement()).isEqualTo(FeedbackAcknowledgement.PENDING);
        assertThat(recorded.acknowledgementEventId()).isEqualTo(50L);
        assertThat(recorded.acknowledgement()).isEqualTo(FeedbackAcknowledgement.PENDING);
    }

    @Test
    void supersedingIsRecordedInTheEarlierRecordsHistory() {
        WorkflowFeedback previous = new WorkflowFeedback();
        previous.setId(5L);
        previous.setReference("FB4");
        previous.setState(FeedbackState.EFFECTIVE);
        when(feedbackRepository.findEffective(10L, FeedbackTargetType.OUTPUT, null, "alice"))
                .thenReturn(java.util.List.of(previous));

        service.submit(SCOPE, source(), FeedbackTarget.output("O1234"), FeedbackVerdict.USEFUL, "",
                "alice");

        ArgumentCaptor<WorkflowFeedbackEvent> events = ArgumentCaptor.forClass(WorkflowFeedbackEvent.class);
        verify(eventRepository, org.mockito.Mockito.times(2)).save(events.capture());
        WorkflowFeedbackEvent superseded = events.getAllValues().getFirst();
        assertThat(superseded.getFeedbackId()).isEqualTo(5L);
        assertThat(superseded.getEventType()).isEqualTo(FeedbackEventType.SUPERSEDED);
        assertThat(superseded.getRelatedReference()).isEqualTo("FB7");
        assertThat(superseded.getAcknowledgement()).isNull();
    }

    @Test
    void aRedeliveryReportsHowTheFirstConfirmationWent() {
        WorkflowFeedback existing = new WorkflowFeedback();
        existing.setId(3L);
        existing.setReference("FB3");
        existing.setTargetReference("O1234");
        existing.setTargetType(FeedbackTargetType.OUTPUT);
        existing.setVerdict(FeedbackVerdict.USEFUL);
        existing.setActor("alice");
        when(feedbackRepository.findBySourceKey(source().sourceKey())).thenReturn(Optional.of(existing));
        WorkflowFeedbackEvent submitted = new WorkflowFeedbackEvent();
        submitted.setId(8L);
        submitted.setAcknowledgement(FeedbackAcknowledgement.FAILED);
        when(eventRepository.findFirstByFeedbackIdAndEventTypeOrderByIdDesc(3L,
                FeedbackEventType.SUBMITTED)).thenReturn(Optional.of(submitted));

        FeedbackService.Recorded recorded = service.submit(SCOPE, source(),
                FeedbackTarget.output("O1234"), FeedbackVerdict.USEFUL, "", "alice");

        assertThat(recorded.duplicate()).isTrue();
        assertThat(recorded.acknowledgementEventId()).isEqualTo(8L);
        assertThat(recorded.acknowledgement()).isEqualTo(FeedbackAcknowledgement.FAILED);
    }

    @Test
    void onlyARefusedConfirmationCanBeClaimedForAnotherAttempt() {
        when(eventRepository.transitionAcknowledgement(eq(8L), eq(FeedbackAcknowledgement.FAILED),
                eq(FeedbackAcknowledgement.PENDING), any())).thenReturn(1, 0);

        assertThat(service.claimAcknowledgementRetry(8L)).isTrue();
        assertThat(service.claimAcknowledgementRetry(8L)).isFalse();
        assertThat(service.claimAcknowledgementRetry(null)).isFalse();
    }

    @Test
    void aConfirmationOutcomeOnlyReplacesAPendingState() {
        service.markAcknowledgement(8L, FeedbackAcknowledgement.UNCERTAIN);
        service.markAcknowledgement(8L, FeedbackAcknowledgement.PENDING);

        verify(eventRepository).transitionAcknowledgement(eq(8L), eq(FeedbackAcknowledgement.PENDING),
                eq(FeedbackAcknowledgement.UNCERTAIN), any());
        verify(eventRepository, org.mockito.Mockito.times(1))
                .transitionAcknowledgement(any(), any(), any(), any());
    }

    @Test
    void aFullPageCarriesACursorThatContinuesBelowItsLastRow() {
        java.util.List<WorkflowFeedback> rows = new java.util.ArrayList<>();
        for (long id = 30; id > 30 - FeedbackService.MAX_PAGE_SIZE - 1; id--) {
            rows.add(row(id));
        }
        when(feedbackRepository.pageForPullRequest(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), any())).thenReturn(rows);

        FeedbackService.ListPage page = service.list(SCOPE, false, null, null);

        assertThat(page.rows()).hasSize(FeedbackService.MAX_PAGE_SIZE);
        long lastId = page.rows().getLast().id();
        assertThat(FeedbackCursor.decode(page.nextCursor(), SCOPE, false)).isEqualTo(lastId);

        service.list(SCOPE, false, null, page.nextCursor());
        verify(feedbackRepository).pageForPullRequest(1L, 2L, "acme", "web", 9L, lastId,
                org.springframework.data.domain.PageRequest.of(0, FeedbackService.MAX_PAGE_SIZE + 1));
    }

    @Test
    void theLastPageHasNoCursor() {
        when(feedbackRepository.pageForPullRequest(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), any())).thenReturn(java.util.List.of(row(3L)));

        assertThat(service.list(SCOPE, false, null, null).nextCursor()).isNull();
    }

    @Test
    void aCursorOfAnotherListingIsRefused() {
        String otherPullRequest = FeedbackCursor.encode(
                new FeedbackScope(1L, 2L, "acme", "web", 10L), false, 40L);
        String repositoryWide = FeedbackCursor.encode(SCOPE, true, 40L);

        assertThatThrownBy(() -> service.list(SCOPE, false, null, otherPullRequest))
                .isInstanceOfSatisfying(FeedbackRejectedException.class,
                        rejection -> assertThat(rejection.getCode()).isEqualTo("invalid-cursor"));
        assertThatThrownBy(() -> service.list(SCOPE, false, null, repositoryWide))
                .isInstanceOf(FeedbackRejectedException.class);
        assertThatThrownBy(() -> service.list(SCOPE, false, null, "not-a-cursor!"))
                .isInstanceOf(FeedbackRejectedException.class);
        verify(feedbackRepository, never()).pageForPullRequest(anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void showIncludesTheRecordsHistory() {
        WorkflowFeedback record = row(9L);
        when(feedbackRepository.findByReference("FB9")).thenReturn(Optional.of(record));
        WorkflowFeedbackEvent submitted = new WorkflowFeedbackEvent();
        submitted.setEventType(FeedbackEventType.SUBMITTED);
        submitted.setActor("alice");
        submitted.setCreatedAt(java.time.Instant.parse("2026-11-14T10:00:00Z"));
        when(eventRepository.findByFeedbackIdOrderByIdAsc(9L)).thenReturn(java.util.List.of(submitted));

        FeedbackService.Detail detail = service.show(SCOPE, "FB9").orElseThrow();

        assertThat(detail.summary().reference()).isEqualTo("FB9");
        assertThat(detail.history()).extracting(FeedbackService.HistoryEntry::type)
                .containsExactly(FeedbackEventType.SUBMITTED);
    }

    private static WorkflowFeedback row(long id) {
        WorkflowFeedback record = new WorkflowFeedback();
        record.setId(id);
        record.setReference("FB" + id);
        record.setTargetReference("O1234");
        record.setTargetType(FeedbackTargetType.OUTPUT);
        record.setVerdict(FeedbackVerdict.USEFUL);
        record.setState(FeedbackState.EFFECTIVE);
        record.setActor("alice");
        record.setGitIntegrationId(1L);
        record.setBotId(2L);
        record.setRepoOwner("acme");
        record.setRepoName("web");
        record.setPrNumber(9L);
        record.setCreatedAt(java.time.Instant.parse("2026-11-14T10:00:00Z"));
        return record;
    }

    @Test
    void theSourceKeyFitsItsColumnWhateverTheRepositoryIsCalled() {
        FeedbackSource longName = new FeedbackSource(1L, 2L, "a-rather-long-organisation-name",
                "an-even-longer-repository-name-that-goes-on-and-on", null,
                FeedbackSource.SubjectKind.PR, 12345L, "INLINE_COMMENT", "9876543210");

        assertThat(longName.sourceKey()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(longName.sourceKey()).isNotEqualTo(new FeedbackSource(1L, 2L,
                "a-rather-long-organisation-name", "an-even-longer-repository-name-that-goes-on-and-on",
                null, FeedbackSource.SubjectKind.PR, 12345L, "INLINE_COMMENT", "9876543211").sourceKey());
    }

    private static FeedbackSource source() {
        return new FeedbackSource(1L, 2L, "acme", "web", "repo-42",
                FeedbackSource.SubjectKind.PR, 9L, "PR_COMMENT", "777");
    }

    private static ReviewFinding finding(int ordinal, boolean withdrawn) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(30L);
        finding.setOrdinal(ordinal);
        finding.setRevision(1);
        finding.setWithdrawn(withdrawn);
        return finding;
    }
}
