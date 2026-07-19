package org.remus.giteabot.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(statements = "DELETE FROM pr_audit_events", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PrAuditEventToolCallTest {

    @Autowired
    private PrAuditEventService auditService;

    @Test
    void shouldEmitToolCallEventWithAllFields() {
        auditService.emitToolCall(
            1L, "owner", "repo", 1L, null,
            100L, "session-abc",
            3, "cat",
            "{\"path\":\"/tmp/test.txt\"}",
            "# Hello World\n\nSome content...",
            true,
            250,
            1200L, 300L);

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "owner", "repo", 1L);
        assertThat(events).hasSize(1);
        PrAuditEvent e = events.getFirst();
        assertThat(e.getEventType()).isEqualTo(AuditEventType.TOOL_CALL_EXECUTED);
        assertThat(e.getDurationMs()).isEqualTo(250);
        assertThat(e.getInputTokens()).isEqualTo(1200);
        assertThat(e.getOutputTokens()).isEqualTo(300);
    }

    @Test
    void shouldTruncateArgumentsAndResult() {
        String hugeArgs = "x".repeat(5000);
        String hugeResult = "y".repeat(5000);

        auditService.emitToolCall(
            1L, "owner2", "repo", 2L, null,
            null, null, 1, "cat", hugeArgs, hugeResult,
            true, 100, null, null);

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "owner2", "repo", 2L);
        String payload = events.getFirst().getEventPayloadJson();
        assertThat(payload).contains("...");
        assertThat(payload.length()).isLessThan(5000);
    }

    @Test
    void shouldChainToolCallsInRun() {
        auditService.record(PrAuditEvent.builder()
            .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
            .eventTimestamp(Instant.now())
            .botId(1L).repoOwner("o").repoName("r").prNumber(3L)
            .actorType(ActorType.SYSTEM.name()).actorId("test")
            .build());

        auditService.emitToolCall(1L, "o", "r", 3L, null,
            null, null, 1, "read", "{}", "content", true, 10, 50L, 10L);

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "o", "r", 3L);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getPreviousHash()).isNull();
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getHash());
    }

    @Test
    void shouldStoreRoundNumberInPayload() {
        auditService.emitToolCall(1L, "o", "r", 4L, null,
            null, null, 5, "cat", "{}", "ok", true, 0, null, null);

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "o", "r", 4L);
        String payload = events.getFirst().getEventPayloadJson();
        assertThat(payload).contains("\"round\":5");
        assertThat(payload).contains("\"tool_name\":\"cat\"");
        assertThat(payload).contains("\"success\":true");
    }
}
