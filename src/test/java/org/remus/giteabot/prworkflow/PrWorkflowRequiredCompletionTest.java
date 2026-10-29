package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationRepository;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GitIntegrationRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.AuditLogGarbageCollector;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.eventhook.EventHookDeliveryWorker;
import org.remus.giteabot.eventhook.EventHookDelivery;
import org.remus.giteabot.eventhook.EventHookDeliveryRepository;
import org.remus.giteabot.eventhook.EventHookDeliveryGarbageCollector;
import org.remus.giteabot.eventhook.DeliveryStatus;
import org.remus.giteabot.eventhook.EventHookEndpoint;
import org.remus.giteabot.eventhook.EventHookEndpointService;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.eventhook.EventHookProperties;
import org.remus.giteabot.eventhook.EventHookRetrySweeper;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.remus.giteabot.systemsettings.SystemPromptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real H2 transactions/migrations; only the outgoing delivery boundary is mocked. */
@SpringBootTest(classes = PrWorkflowRequiredCompletionTest.PersistenceConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:required-completion;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/h2",
        "audit.retention=PT0S",
        "eventhook.retention.keep-last=0"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrWorkflowRequiredCompletionTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("org.remus.giteabot")
    @EnableJpaRepositories("org.remus.giteabot")
    @Import({PrWorkflowRunService.class, PrWorkflowCompletionService.class, PrAuditEventService.class,
            EventHookPublisher.class, EventHookEndpointService.class, EventHookProperties.class,
            EventHookRetrySweeper.class, EncryptionService.class, AuditLogGarbageCollector.class,
            EventHookDeliveryGarbageCollector.class})
    static class PersistenceConfiguration {
    }

    @Autowired private PrWorkflowRunService runs;
    @Autowired private PrAuditEventService audits;
    @Autowired private PrWorkflowCompletionService completion;
    @Autowired private EventHookPublisher publisher;
    @Autowired private EventHookEndpointService endpoints;
    @Autowired private EventHookDeliveryRepository deliveries;
    @Autowired private EventHookRetrySweeper sweeper;
    @Autowired private AuditLogGarbageCollector auditGc;
    @Autowired private EventHookDeliveryGarbageCollector deliveryGc;
    @Autowired private TransactionTemplate tx;
    @Autowired private BotRepository bots;
    @Autowired private AiIntegrationRepository ais;
    @Autowired private GitIntegrationRepository gits;
    @Autowired private SystemPromptRepository prompts;
    @Autowired private BotToolConfigurationRepository tools;
    @MockitoBean private EventHookDeliveryWorker deliveryWorker;

    private Bot bot;
    private PrWorkflowRun run;

    @BeforeEach
    void createRun() {
        reset(deliveryWorker);
        String suffix = UUID.randomUUID().toString();
        AiIntegration ai = new AiIntegration();
        ai.setName("required-ai-" + suffix);
        ai.setProviderType("OPENAI");
        ai.setApiUrl("http://localhost");
        ai.setModel("test");
        ais.save(ai);
        GitIntegration git = new GitIntegration();
        git.setName("required-git-" + suffix);
        git.setUrl("http://localhost");
        git.setToken("test");
        git.setUsername("required-bot");
        gits.save(git);
        bot = new Bot();
        bot.setName("required-bot-" + suffix);
        bot.setAiIntegration(ai);
        bot.setGitIntegration(git);
        bot.setSystemPrompt(prompts.findAll().getFirst());
        bot.setToolConfiguration(tools.findByDefaultEntryTrue().orElseThrow());
        bot = bots.save(bot);
        run = runs.start(bot.getId(), "acme", "project", 42L, "agent-review");
    }

    @Test
    void completionCommitsGenericAuditAndDeliveriesThenDispatchesAndIdenticalReplayIsANoOp() {
        EventHookEndpoint endpoint = endpoint();
        PrWorkflowEventKey key = completionKey();
        tx.executeWithoutResult(status -> {
            PrWorkflowCompletionResult result = completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                    PrWorkflowRunStatus.SUCCESS, "done", bot);
            assertThat(result.applied()).isTrue();
            assertThat(result.run().getRequiredCompletionHash()).hasSize(64);
            assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.SUCCESS);
            assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L))
                    .singleElement().extracting(PrAuditEvent::getEventType)
                    .isEqualTo(AuditEventType.PR_WORKFLOW_RUN_COMPLETED);
            assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(key.value())).hasSize(1);
            verifyNoInteractions(deliveryWorker);
        });

        EventHookDelivery delivery = deliveries.findByEventKeyOrderByEndpointIdAsc(key.value()).getFirst();
        assertThat(delivery.getEndpointId()).isEqualTo(endpoint.getId());
        assertThat(delivery.getPayloadJson()).contains("\"eventKey\":\"" + key.value() + "\"");
        verify(deliveryWorker).deliverAsync(delivery.getId());
        var finishedAt = runs.getById(run.getId()).getFinishedAt();
        var audit = audits.findRequired(key).orElseThrow();
        clearInvocations(deliveryWorker);

        assertThat(completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "done", bot).applied()).isFalse();

        assertThat(runs.getById(run.getId()).getFinishedAt()).isEqualTo(finishedAt);
        assertThat(audits.findRequired(key).orElseThrow().getHash()).isEqualTo(audit.getHash());
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(key.value()))
                .singleElement().extracting(EventHookDelivery::getDeliveryUuid).isEqualTo(delivery.getDeliveryUuid());
        verifyNoInteractions(deliveryWorker);
        assertThatThrownBy(() -> completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "changed", bot)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiredOutboxReplaysPerEndpointAndRejectsChangedPayloadEvenWhenEndpointWasDisabled() {
        EventHookEndpoint endpoint = endpoint();
        endpoint();
        PrWorkflowEventKey key = completionKey();
        List<EventHookDelivery> first = tx.execute(status -> publisher.enqueueRequired(key,
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of("result", "confirmed")));
        clearInvocations(deliveryWorker);
        endpoint.setEnabled(false);
        endpoints.save(endpoint, null, null);
        List<EventHookDelivery> replay = tx.execute(status -> publisher.enqueueRequired(key,
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of("result", "confirmed")));

        assertThat(replay).hasSize(2);
        assertThat(replay).extracting(EventHookDelivery::getId)
                .containsExactlyInAnyOrderElementsOf(first.stream().map(EventHookDelivery::getId).toList());
        for (EventHookDelivery delivery : replay) {
            assertThat(delivery.getPayloadJson()).isEqualTo(first.stream()
                    .filter(original -> original.getId().equals(delivery.getId())).findFirst().orElseThrow().getPayloadJson());
        }
        verifyNoInteractions(deliveryWorker);
        assertThatThrownBy(() -> tx.execute(status -> publisher.enqueueRequired(key,
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of("result", "different"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(key.value())).hasSize(2);
    }

    @Test
    void requiredOutboxRejectsIncompatibleKeyAndHookTypeWithoutPersistingAnything() {
        EventHookEndpoint endpoint = endpoint();
        endpoint.setEventTypes("ISSUE_ASSIGNMENT_STARTED");
        endpoints.save(endpoint, null, null);

        assertThatThrownBy(() -> tx.execute(status -> publisher.enqueueRequired(completionKey(),
                EventHookEventType.ISSUE_ASSIGNMENT_STARTED, bot, Map.of("issueNumber", 42L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(status -> publisher.enqueueRequired(
                new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, run.getId(), null),
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(deliveries.findTop50ByEndpointIdOrderByIdDesc(endpoint.getId())).isEmpty();
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).isEmpty();
        assertThat(runs.getById(run.getId()).getRequiredCompletionHash()).isNull();
        assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.RUNNING);
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void requiredFindingHookKeepsItsFindingKeyRatherThanUsingGenericCompletionIdentity() {
        endpoint();
        PrWorkflowEventKey key = new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, run.getId(), "F1");
        List<EventHookDelivery> queued = tx.execute(status -> publisher.enqueueRequired(key,
                EventHookEventType.AGENT_REVIEW_FINDING_DETECTED, bot, Map.of("findingId", "F1")));

        assertThat(queued).singleElement().extracting(EventHookDelivery::getEventKey).isEqualTo(key.value());
        assertThat(queued.getFirst().getEventType()).isEqualTo("prworkflow.agentreview.finding.detected");
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        verify(deliveryWorker).deliverAsync(queued.getFirst().getId());
    }

    @Test
    void ordinaryCleanupDoesNotDestroyGenericCompletionReplayReceipt() {
        endpoint();
        PrWorkflowCompletionResult applied = completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "done", bot);
        String hash = applied.run().getRequiredCompletionHash();
        var finishedAt = applied.run().getFinishedAt();
        tx.executeWithoutResult(status -> {
            EventHookDelivery delivered = deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value()).getFirst();
            delivered.setStatus(DeliveryStatus.SUCCESS);
            deliveries.saveAndFlush(delivered);
            auditGc.runGarbageCollection();
            deliveryGc.runGarbageCollection();
        });
        assertThat(audits.findRequired(completionKey())).isEmpty();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        bot.setName("renamed-" + bot.getName());
        bots.save(bot);
        clearInvocations(deliveryWorker);

        PrWorkflowCompletionResult replay = completion.complete(run.getId(), PrWorkflowRunStatus.WAITING_DEPLOY,
                PrWorkflowRunStatus.SUCCESS, "done", bot);

        assertThat(replay.applied()).isFalse();
        assertThat(replay.run().getRequiredCompletionHash()).isEqualTo(hash).hasSize(64);
        assertThat(replay.run().getFinishedAt()).isEqualTo(finishedAt);
        assertThat(audits.findRequired(completionKey())).isEmpty();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        verifyNoInteractions(deliveryWorker);
        assertThatThrownBy(() -> completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "changed", bot)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.FAILED, "done", bot)).isInstanceOf(IllegalStateException.class);
        assertThat(runs.getById(run.getId()).getRequiredCompletionHash()).isEqualTo(hash);
    }

    @Test
    void enablingFirstSubscriberAfterCompletionCannotPublishOnReplay() {
        assertThat(completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "done", bot).applied()).isTrue();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        auditGc.runGarbageCollection();
        EventHookEndpoint endpoint = endpoint();
        clearInvocations(deliveryWorker);

        assertThat(completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "done", bot).applied()).isFalse();

        assertThat(audits.findRequired(completionKey())).isEmpty();
        assertThat(deliveries.findTop50ByEndpointIdOrderByIdDesc(endpoint.getId())).isEmpty();
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void actualRequiredInsertConstraintFailureRollsBackRunAuditAndOutboxTogether() {
        endpoint();
        PrWorkflowEventKey key = new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, run.getId(), null);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING, PrWorkflowRunStatus.SUCCESS, "done", bot);
            PrAuditEvent invalid = event("confirmed");
            invalid.setActorId("x".repeat(256)); // V34's real VARCHAR(255) constraint, not a mocked save failure.
            audits.recordRequired(key, invalid);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.RUNNING);
        assertThat(runs.getById(run.getId()).getFinishedAt()).isNull();
        assertThat(runs.getById(run.getId()).getRequiredCompletionHash()).isNull();
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).isEmpty();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        verifyNoInteractions(deliveryWorker);
        assertThat(completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "done", bot).applied()).isTrue();
        assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.SUCCESS);
        assertThat(runs.getById(run.getId()).getRequiredCompletionHash()).hasSize(64);
    }

    @Test
    void lostAfterCommitKickLeavesDurablePendingDeliveryForExistingSweeper() {
        endpoint();
        doThrow(new IllegalStateException("executor unavailable")).when(deliveryWorker).deliverAsync(org.mockito.ArgumentMatchers.anyLong());
        completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING, PrWorkflowRunStatus.SUCCESS, "done", bot);
        EventHookDelivery delivery = deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value()).getFirst();
        assertThat(delivery.getAttempts()).isZero();
        reset(deliveryWorker);

        sweeper.sweep();

        verify(deliveryWorker).deliverAsync(delivery.getId());
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value()))
                .singleElement().extracting(EventHookDelivery::getPayloadJson).isEqualTo(delivery.getPayloadJson());
    }

    @Test
    void expectedStatusAndRequiredTransactionAreEnforcedBeforeAnyInsert() {
        assertThatThrownBy(() -> completion.complete(run.getId(), PrWorkflowRunStatus.WAITING_DEPLOY,
                PrWorkflowRunStatus.SUCCESS, "done", bot)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> audits.recordRequired(
                new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, run.getId(), null), event("confirmed")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> publisher.enqueueRequired(completionKey(),
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).isEmpty();
    }

    @Test
    void concurrentIdenticalCompletionsHaveOneAuditAndOneDelivery() throws Exception {
        endpoint();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                        PrWorkflowRunStatus.SUCCESS, "done", bot);
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                        PrWorkflowRunStatus.SUCCESS, "done", bot);
            });
            PrWorkflowCompletionResult firstResult = first.get(10, TimeUnit.SECONDS);
            PrWorkflowCompletionResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.run().getStatus()).isEqualTo(PrWorkflowRunStatus.SUCCESS);
            assertThat(secondResult.run().getStatus()).isEqualTo(PrWorkflowRunStatus.SUCCESS);
            assertThat(firstResult.applied()).isNotEqualTo(secondResult.applied());
        }
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).hasSize(1);
        EventHookDelivery delivery = deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value()).getFirst();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).hasSize(1);
        verify(deliveryWorker).deliverAsync(delivery.getId());
    }

    @Test
    void completionReloadsStateEvenIfCallerAlreadyCachedTheRun() {
        TransactionTemplate independent = new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.RUNNING);
            independent.executeWithoutResult(other -> completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING,
                    PrWorkflowRunStatus.FAILED, "failed", bot));
            completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING, PrWorkflowRunStatus.SUCCESS, "done", bot);
        })).isInstanceOf(IllegalStateException.class);
        assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.FAILED);
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).hasSize(1);
    }

    @Test
    void failedCompletionEmitsOnlyGenericFailureAndLegacyTerminalRunIsNotBackfilled() {
        endpoint();
        completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING, PrWorkflowRunStatus.FAILED, "failed", bot);
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L))
                .singleElement().extracting(PrAuditEvent::getEventType).isEqualTo(AuditEventType.PR_WORKFLOW_RUN_COMPLETED);
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value()))
                .singleElement().extracting(EventHookDelivery::getEventType).isEqualTo("prworkflow.failed");
        PrWorkflowRun legacy = runs.start(bot.getId(), "acme", "project", 43L, "agent-review");
        runs.complete(legacy.getId(), PrWorkflowRunStatus.SUCCESS, "legacy");
        assertThat(runs.getById(legacy.getId()).getRequiredCompletionHash()).isNull();
        assertThatThrownBy(() -> completion.complete(legacy.getId(), PrWorkflowRunStatus.RUNNING,
                PrWorkflowRunStatus.SUCCESS, "legacy", bot)).isInstanceOf(IllegalStateException.class);
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 43L)).isEmpty();
    }

    @Test
    void oversizedRequiredHookRollsBackCompletionInsteadOfSkippingTheHook() {
        endpoint();
        PrWorkflowEventKey key = new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, run.getId(), "F1");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            completion.complete(run.getId(), PrWorkflowRunStatus.RUNNING, PrWorkflowRunStatus.SUCCESS, "done", bot);
            publisher.enqueueRequired(key, EventHookEventType.AGENT_REVIEW_FINDING_DETECTED, bot,
                    Map.of("oversized", "x".repeat(70_000)));
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.getById(run.getId()).getStatus()).isEqualTo(PrWorkflowRunStatus.RUNNING);
        assertThat(runs.getById(run.getId()).getRequiredCompletionHash()).isNull();
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).isEmpty();
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void requiredPayloadSerializationDoesNotUseLegacySilentFallback() {
        Object unserializable = new Object() {
            public String getValue() {
                throw new IllegalArgumentException("Cannot serialize this value");
            }
        };
        assertThatThrownBy(() -> PrAuditEventService.toJsonRequired(Map.of("value", unserializable)))
                .isInstanceOf(IllegalArgumentException.class);
        endpoint();
        assertThatThrownBy(() -> tx.execute(status -> publisher.enqueueRequired(completionKey(),
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot, Map.of("value", unserializable))))
                .isInstanceOf(RuntimeException.class);
        assertThat(deliveries.findByEventKeyOrderByEndpointIdAsc(completionKey().value())).isEmpty();
        verifyNoInteractions(deliveryWorker);
    }

    private PrWorkflowEventKey completionKey() {
        return new PrWorkflowEventKey(AuditEventType.PR_WORKFLOW_RUN_COMPLETED, run.getId(), null);
    }

    private EventHookEndpoint endpoint() {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setName("required receiver");
        endpoint.setUrl("http://localhost/unused");
        endpoint.setBotId(bot.getId());
        endpoint.setEventTypes("PR_WORKFLOW_COMPLETED,PR_WORKFLOW_FAILED,AGENT_REVIEW_FINDING_DETECTED");
        return endpoints.save(endpoint, null, null);
    }

    @Test
    void requiredAuditReplaysWithoutExtendingTheHashChainAndRejectsChangedPayload() {
        PrWorkflowEventKey key = new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, run.getId(), null);
        PrAuditEvent first = tx.execute(status -> audits.recordRequired(key, event("confirmed")));
        PrAuditEvent replay = tx.execute(status -> audits.recordRequired(key, event("confirmed")));
        assertThat(tx.execute(status -> audits.recordRequired(key, first)).getId()).isEqualTo(first.getId());

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getHash()).isEqualTo(first.getHash());
        assertThat(replay.getEventTimestamp()).isEqualTo(first.getEventTimestamp());
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L)).hasSize(1);
        assertThatThrownBy(() -> tx.execute(status -> audits.recordRequired(key, event("changed"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(audits.findByBotAndRepoAndPr(bot.getId(), "acme", "project", 42L))
                .singleElement().extracting(PrAuditEvent::getHash).isEqualTo(first.getHash());
    }

    private PrAuditEvent event(String result) {
        return PrAuditEvent.builder().eventType(AuditEventType.REVIEW_COMPLETED)
                .botId(bot.getId()).repoOwner("acme").repoName("project").prNumber(42L)
                .runId(run.getId()).actorType("BOT").actorId(bot.getName())
                .eventPayloadJson(PrAuditEventService.toJson(Map.of("result", result))).build();
    }
}
