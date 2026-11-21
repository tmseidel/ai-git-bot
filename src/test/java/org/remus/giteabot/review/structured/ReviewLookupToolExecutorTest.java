package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AuditRun;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review lookups are recorded as tool calls of the current AI interaction. */
class ReviewLookupToolExecutorTest {

    private final PublishedReviewLookup lookup = mock(PublishedReviewLookup.class);
    private final ReviewLookupToolExecutor executor = new ReviewLookupToolExecutor(lookup);
    private final List<AuditRun.ToolCallRecord> records = new ArrayList<>();

    @AfterEach
    void tearDown() {
        AiAuditContext.clear();
    }

    private void openRecordingRun() {
        AuditRun run = AiAuditContext.openRun("agentic-review", null);
        run.attach(1L, new AuditRun.Sink() {
            @Override
            public void runFinished(AuditRun finished) {
            }

            @Override
            public void toolCallFinished(AuditRun current, AuditRun.ToolCallRecord record) {
                records.add(record);
            }
        });
        run.beginAttempt();
        run.setInteractionId(10L);
    }

    @Test
    void lookupsAreRecordedWithTheirOutcome() {
        when(lookup.list(1L, 2L, "owner", "repo", 3L)).thenReturn("[]");
        ReviewLookupInvocation invocation = new ReviewLookupInvocation(1L, 2L, "owner", "repo", 3L, () -> false);
        openRecordingRun();

        assertTrue(executor.execute(invocation, ReviewToolCall.of(ReviewLookupToolExecutor.LOOKUP,
                new ObjectMapper().createObjectNode())).success());
        assertFalse(executor.execute(null, ReviewToolCall.of(ReviewLookupToolExecutor.LOOKUP, null)).success());

        assertEquals(2, records.size());
        assertEquals(ReviewLookupToolExecutor.LOOKUP, records.get(0).toolName());
        assertTrue(records.get(0).success());
        assertFalse(records.get(1).success());
        assertEquals("Review lookup is not available in this run", records.get(1).errorMessage());
    }
}
