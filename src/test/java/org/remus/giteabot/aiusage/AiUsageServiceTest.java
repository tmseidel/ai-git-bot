package org.remus.giteabot.aiusage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AuditRun;
import org.remus.giteabot.ai.AuditTrigger;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.config.AiUsageProperties;
import org.remus.giteabot.workflowrun.RunStatus;
import org.remus.giteabot.workflowrun.WorkflowRun;
import org.remus.giteabot.workflowrun.WorkflowRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiUsageServiceTest {

    @Mock
    private AiInteractionRepository interactionRepository;

    @Mock
    private AiToolCallRepository toolCallRepository;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private AiUsageProperties usageProperties;

    private AiUsageService service;

    private static final AuditTrigger TRIGGER = new AuditTrigger(1L, "bot", "my-openai", "my-gitea",
            "owner", "repo", TriggerKind.ISSUE, 7L);

    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new AiUsageService(interactionRepository, toolCallRepository, runRepository, usageProperties);
        when(runRepository.save(any())).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            run.setId(ids.incrementAndGet());
            return run;
        });
        when(interactionRepository.save(any())).thenAnswer(inv -> {
            AiInteraction interaction = inv.getArgument(0);
            interaction.setId(ids.incrementAndGet());
            return interaction;
        });
        // Like JPA's getReferenceById: an entity that only carries its id.
        when(runRepository.getReferenceById(any())).thenAnswer(inv -> {
            WorkflowRun run = new WorkflowRun();
            run.setId(inv.getArgument(0));
            return run;
        });
        when(interactionRepository.getReferenceById(any())).thenAnswer(inv -> {
            AiInteraction interaction = new AiInteraction();
            interaction.setId(inv.getArgument(0));
            return interaction;
        });
    }

    @AfterEach
    void tearDown() {
        AiAuditContext.clear();
    }

    // ------------------------------------------------------------------ recording

    @Test
    void firstAttemptCreatesTheRunFromItsTrigger() {
        AuditTrigger trigger = new AuditTrigger(7L, "reviewer", "my-anthropic", "my-gitea",
                "owner", "repo", TriggerKind.PULL_REQUEST, 42L);
        try (AuditRun run = AiAuditContext.openRun("agentic-review", trigger)) {
            run.beginCall();
            service.attemptStarted("my-anthropic");

            ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runRepository).save(runCaptor.capture());
            WorkflowRun saved = runCaptor.getValue();
            assertEquals("agentic-review", saved.getWorkflowType());
            assertEquals(7L, saved.getBotId());
            assertEquals("reviewer", saved.getBotName());
            assertEquals("my-gitea", saved.getGitIntegrationName());
            assertEquals("owner", saved.getRepoOwner());
            assertEquals(TriggerKind.PULL_REQUEST, saved.getTriggerKind());
            assertEquals(42L, saved.getTriggerNumber());
            assertEquals("owner/repo#42", saved.getSessionId());
            assertEquals(RunStatus.RUNNING, saved.getState());

            ArgumentCaptor<AiInteraction> captor = ArgumentCaptor.forClass(AiInteraction.class);
            verify(interactionRepository).save(captor.capture());
            AiInteraction interaction = captor.getValue();
            assertEquals(saved.getId(), interaction.getWorkflowRun().getId());
            assertEquals(1, interaction.getRoundNumber());
            assertEquals(1, interaction.getAttempt());
            assertEquals(RunStatus.RUNNING, interaction.getStatus());
            assertEquals("owner/repo#42", interaction.getSessionId());
        }
    }

    @Test
    void failedAttemptStoresMessageAndStackTrace() {
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            run.beginCall();
            service.attemptStarted("my-anthropic");
            service.attemptFailed(new IllegalStateException("401 Unauthorized"));

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> stackTrace = ArgumentCaptor.forClass(String.class);
            verify(interactionRepository).finish(any(), eq(RunStatus.ERROR), anyLong(), isNull(),
                    eq(0L), eq(0L), eq(0L), eq(0L), message.capture(), stackTrace.capture(), isNull(), isNull());
            assertEquals("401 Unauthorized", message.getValue());
            assertTrue(stackTrace.getValue().contains("IllegalStateException"));
        }
    }

    @Test
    void failureWithoutMessageIsRecordedWithClassName() {
        AiAuditContext.openRun("review", TRIGGER);
        service.attemptStarted("my-anthropic");
        service.attemptFailed(new IllegalStateException());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(interactionRepository).finish(any(), eq(RunStatus.ERROR), anyLong(), isNull(),
                anyLong(), anyLong(), anyLong(), anyLong(), message.capture(), any(), isNull(), isNull());
        assertEquals(IllegalStateException.class.getName(), message.getValue());
    }

    @Test
    void callWithoutAnOpenRunIsNotRecorded() {
        service.attemptStarted("my-openai");
        service.usageReported(1, 2, 0, 0, null, null);
        service.attemptSucceeded(StopReason.END_TURN);

        verifyNoInteractions(runRepository, interactionRepository);
    }

    @Test
    void usageIsStoredWhenTheAttemptEnds() {
        AiAuditContext.openRun("review", TRIGGER);
        service.attemptStarted("my-openai");
        service.usageReported(5, 6, 1, 2, null, null);
        service.attemptSucceeded(StopReason.END_TURN);

        verify(interactionRepository).finish(any(), eq(RunStatus.SUCCEEDED), anyLong(), eq("END_TURN"),
                eq(5L), eq(6L), eq(1L), eq(2L), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void usageReportedOutsideAnAttemptIsIgnored() {
        service.usageReported(5, 6, 0, 0, null, null);

        verifyNoInteractions(interactionRepository, runRepository);
    }

    @Test
    void rawPayloadsAreDroppedWhenCaptureIsDisabled() {
        AiAuditContext.openRun("review", TRIGGER);
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(false);
        service.attemptStarted("x");
        service.usageReported(1, 1, 0, 0, "{\"req\":1}", "{\"res\":1}");
        service.attemptSucceeded(StopReason.END_TURN);

        verify(interactionRepository).finish(any(), any(), anyLong(), any(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), isNull(), isNull());
    }

    @Test
    void rawPayloadsAreTruncatedToMaxLength() {
        AiAuditContext.openRun("review", TRIGGER);
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(true);
        when(usageProperties.getEffectiveMaxRawPayloadLength()).thenReturn(5);
        service.attemptStarted("x");
        service.usageReported(1, 1, 0, 0, "abcdefgh", "xyz");
        service.attemptSucceeded(StopReason.END_TURN);

        verify(interactionRepository).finish(any(), any(), anyLong(), any(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), eq("abcde"), eq("xyz"));
    }

    @Test
    void toolCallArgumentsAreStoredWithoutRawPayloadLogging() {
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(false);
        try (AuditRun run = AiAuditContext.openRun("review", TRIGGER)) {
            run.beginCall();
            service.attemptStarted("x");
            service.attemptSucceeded(StopReason.TOOL_USE);

            service.toolCallFinished(run, new AuditRun.ToolCallRecord("github", "get_file_contents", 40,
                    true, null, "{\"path\":\"a\"}"));

            ArgumentCaptor<AiToolCall> captor = ArgumentCaptor.forClass(AiToolCall.class);
            verify(toolCallRepository).save(captor.capture());
            assertEquals("{\"path\":\"a\"}", captor.getValue().getArguments());
        }
    }

    @Test
    void runWithoutAiCallsIsNeverPersisted() {
        AiAuditContext.openRun("review", TRIGGER).close();

        verifyNoInteractions(runRepository);
    }

    @Test
    void recordingNeverPropagatesPersistenceFailures() {
        AiAuditContext.openRun("review", TRIGGER);
        doThrow(new RuntimeException("db down")).when(runRepository).save(any());

        assertDoesNotThrow(() -> {
            service.attemptStarted("x");
            service.usageReported(1, 2, 0, 0, null, null);
            service.attemptSucceeded(StopReason.END_TURN);
        });
        verify(interactionRepository, never()).finish(any(), any(), anyLong(), any(), anyLong(), anyLong(),
                anyLong(), anyLong(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ queries

    @Test
    void findUsage_fallsBackToStartTimeForUnknownSortColumn() {
        ArgumentCaptor<PageRequest> captor = usagePageRequest();

        service.findUsage(null, null, 0, AiUsageService.PAGE_SIZE, "evil'); DROP TABLE", false);

        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("startedAt", order.getProperty());
        assertEquals(Sort.Direction.DESC, order.getDirection());
        assertEquals(AiUsageService.PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void findUsage_mapsPageSortKeysToInteractionProperties() {
        ArgumentCaptor<PageRequest> captor = usagePageRequest();

        service.findUsage(null, null, 0, 20, "round", true);

        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("roundNumber", order.getProperty());
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void findUsage_usesRequestedPageSize() {
        ArgumentCaptor<PageRequest> captor = usagePageRequest();

        service.findUsage(null, null, 0, 100, "timestamp", false);

        assertEquals(100, captor.getValue().getPageSize());
    }

    @Test
    void findUsage_clampsOversizedPageSizeToMax() {
        ArgumentCaptor<PageRequest> captor = usagePageRequest();

        service.findUsage(null, null, 0, 1_000_000, "timestamp", false);

        assertEquals(AiUsageService.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void findErrors_usesDefaultPageSizeWhenZero() {
        ArgumentCaptor<PageRequest> captor = errorPageRequest();

        service.findErrors(null, null, 0, 0, "timestamp", false);

        assertEquals(AiUsageService.PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void findErrors_appliesRequestedSortAscending() {
        ArgumentCaptor<PageRequest> captor = errorPageRequest();

        service.findErrors(Instant.EPOCH, Instant.now(), 2, AiUsageService.PAGE_SIZE, "aiIntegrationName", true);

        Sort.Order order = captor.getValue().getSort().iterator().next();
        assertEquals("aiIntegrationName", order.getProperty());
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertEquals(2, captor.getValue().getPageNumber());
    }

    @Test
    void findErrors_clampsJustAboveMaxPageSize() {
        ArgumentCaptor<PageRequest> captor = errorPageRequest();

        service.findErrors(null, null, 0, AiUsageService.MAX_PAGE_SIZE + 1, "timestamp", false);

        assertEquals(AiUsageService.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void findRawPayload_readsTheEntryWhenCaptureIsEnabled() {
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(true);
        AiUsageRawPayload payload = new AiUsageRawPayload("req", "res");
        when(interactionRepository.findRawPayloadById(5L)).thenReturn(Optional.of(payload));

        assertEquals(Optional.of(payload), service.findRawPayload(5L));
    }

    @Test
    void findRawPayload_isEmptyWhenCaptureIsDisabled() {
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(false);

        assertTrue(service.findRawPayload(5L).isEmpty());
        verifyNoInteractions(interactionRepository);
    }

    private ArgumentCaptor<PageRequest> usagePageRequest() {
        Page<AiUsageRow> page = new PageImpl<>(List.of());
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        when(interactionRepository.findUsageRows(any(), any(), captor.capture())).thenReturn(page);
        return captor;
    }

    private ArgumentCaptor<PageRequest> errorPageRequest() {
        Page<AiErrorRow> page = new PageImpl<>(List.of());
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        when(interactionRepository.findErrorRows(any(), any(), captor.capture())).thenReturn(page);
        return captor;
    }
}
