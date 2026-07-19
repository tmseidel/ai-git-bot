package org.remus.giteabot.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(statements = "DELETE FROM pr_audit_events", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PrAuditEventServiceTest {

    @Autowired
    private PrAuditEventService auditService;

    @Test
    void shouldEmitAndPersistAuditEvent() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("emitter").repoName("repo-a").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("workflow_key", "review")))
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "emitter", "repo-a", 1L);
        assertThat(events).hasSize(1);
        PrAuditEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.PR_WORKFLOW_RUN_STARTED);
        assertThat(event.getHash()).isNotNull().hasSize(64);
        assertThat(event.getPreviousHash()).isNull();
    }

    @Test
    void shouldProduceDeterministicHash() {
        String hash1 = PrAuditEventService.computeHash("TEST", Instant.EPOCH, "{\"a\":\"1\"}", null);
        String hash2 = PrAuditEventService.computeHash("TEST", Instant.EPOCH, "{\"a\":\"1\"}", null);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void shouldChainHashesAcrossEvents() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("chainer").repoName("repo-b").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .build());

        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_STEP_APPENDED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("chainer").repoName("repo-b").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("step", 1)))
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "chainer", "repo-b", 1L);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getPreviousHash()).isNull();
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getHash());
    }

    @Test
    void shouldQueryByBotRepoPr() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(1L).repoOwner("botr1").repoName("repo-c").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(2L).repoOwner("botr2").repoName("repo-c").prNumber(2L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "botr1", "repo-c", 1L);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getBotId()).isEqualTo(1L);
    }

    @Test
    void shouldQueryByBot() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_RUN_STARTED)
                .botId(1L).repoOwner("bot-unique").repoName("repo-d").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("t").build());

        List<PrAuditEvent> events = auditService.findByBot(1L);
        assertThat(events).hasSize(1);
    }

    @Test
    void shouldEmitStepEventWithStepMetadata() {
        auditService.record(PrAuditEvent.builder()
                .eventType(AuditEventType.PR_WORKFLOW_STEP_APPENDED)
                .eventTimestamp(Instant.now())
                .botId(1L).repoOwner("stepper").repoName("repo-e").prNumber(1L)
                .actorType(ActorType.SYSTEM.name()).actorId("test")
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("run_id", 46L, "step_order", 0)))
                .stepIndex(0).stepName("fetch-diff").stepStatus("INFO")
                .build());

        List<PrAuditEvent> events = auditService.findByBotAndRepoAndPr(1L, "stepper", "repo-e", 1L);
        assertThat(events).hasSize(1);
        PrAuditEvent e = events.getFirst();
        assertThat(e.getStepIndex()).isEqualTo(0);
        assertThat(e.getStepName()).isEqualTo("fetch-diff");
        assertThat(e.getStepStatus()).isEqualTo("INFO");
    }
}
