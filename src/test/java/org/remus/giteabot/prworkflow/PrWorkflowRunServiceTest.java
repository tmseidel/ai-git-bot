package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrWorkflowRunServiceTest {

    @Mock private PrWorkflowRunRepository runRepository;
    @Mock private PrWorkflowStepRepository stepRepository;

    private PrWorkflowRunService service;

    @BeforeEach
    void setUp() {
        service = new PrWorkflowRunService(runRepository, stepRepository);
        lenient().when(runRepository.save(any(PrWorkflowRun.class))).thenAnswer(inv -> {
            PrWorkflowRun r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(1L);
            }
            return r;
        });
    }

    @Test
    void startCreatesRunInRunningStateAndCancelsActiveOnes() {
        PrWorkflowRun previous = new PrWorkflowRun();
        previous.setId(99L);
        previous.setStatus(PrWorkflowRunStatus.RUNNING);
        previous.setBotId(1L);
        previous.setRepoOwner("acme");
        previous.setRepoName("web");
        previous.setPrNumber(7L);
        previous.setWorkflowKey("review");
        when(runRepository
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(previous)));

        PrWorkflowRun created = service.start(1L, "acme", "web", 7L, "review");

        assertEquals(PrWorkflowRunStatus.RUNNING, created.getStatus());
        assertEquals(PrWorkflowRunStatus.CANCELLED, previous.getStatus());
        assertNotNull(previous.getFinishedAt());
        assertTrue(previous.getSummary().startsWith("Superseded"));
    }

    @Test
    void appendStepAttachesAndPersistsTruncatedExcerpt() {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(10L);
        when(runRepository.findById(10L)).thenReturn(Optional.of(run));

        String big = "y".repeat(PrWorkflowRunService.MAX_LOG_EXCERPT_CHARS + 50);
        service.appendStep(10L, "fetch", "INFO", big);

        ArgumentCaptor<PrWorkflowStep> cap = ArgumentCaptor.forClass(PrWorkflowStep.class);
        org.mockito.Mockito.verify(stepRepository).save(cap.capture());
        assertEquals("fetch", cap.getValue().getName());
        assertEquals("INFO", cap.getValue().getStatus());
        assertEquals(PrWorkflowRunService.MAX_LOG_EXCERPT_CHARS,
                cap.getValue().getLogExcerpt().length());
        assertEquals(0, cap.getValue().getStepOrder());
        assertEquals(1, run.getSteps().size());
    }

    @Test
    void completeMovesRunToTerminalStatus() {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(20L);
        run.setStatus(PrWorkflowRunStatus.RUNNING);
        when(runRepository.findById(20L)).thenReturn(Optional.of(run));

        PrWorkflowRun completed = service.complete(20L, PrWorkflowRunStatus.SUCCESS, "All good");

        assertEquals(PrWorkflowRunStatus.SUCCESS, completed.getStatus());
        assertEquals("All good", completed.getSummary());
        assertNotNull(completed.getFinishedAt());
    }

    @Test
    void completeIsIdempotentForTerminalRuns() {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(30L);
        run.setStatus(PrWorkflowRunStatus.SUCCESS);
        run.setSummary("first");
        when(runRepository.findById(30L)).thenReturn(Optional.of(run));

        PrWorkflowRun unchanged = service.complete(30L, PrWorkflowRunStatus.FAILED, "second");

        assertEquals(PrWorkflowRunStatus.SUCCESS, unchanged.getStatus());
        assertEquals("first", unchanged.getSummary());
    }

    @Test
    void getByIdThrowsForUnknownRun() {
        when(runRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getById(99L));
    }

    @Test
    void truncationHelperHandlesNullAndShortValues() {
        assertNull(PrWorkflowRunService.truncate(null, 10));
        assertEquals("abc", PrWorkflowRunService.truncate("abc", 10));
        String trimmed = PrWorkflowRunService.truncate("abcdefghijk", 8);
        assertEquals(8, trimmed.length());
        assertTrue(trimmed.endsWith("..."));
    }
}



