package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class GitIntegrationConcurrencyTest {

    @Autowired private BotService botService;
    @Autowired private GitIntegrationService gitIntegrationService;
    @Autowired private BotRepository botRepository;
    @Autowired private GitIntegrationRepository gitIntegrationRepository;
    @Autowired private AiIntegrationRepository aiIntegrationRepository;
    @Autowired private SystemPromptRepository systemPromptRepository;
    @Autowired private BotToolConfigurationRepository botToolConfigurationRepository;
    @Autowired private TransactionTemplate tx;

    @Test
    void managedKeyStepsCarryCommittedVersions() {
        GitIntegration integration = gitIntegration(false);

        GitIntegration marker = gitIntegrationService.prepareManagedSshKeyCreation(
                integration.getId(), integration.getLockVersion(), 17L, "managed-key");
        assertEquals(integration.getLockVersion() + 1, marker.getLockVersion());

        GitIntegration finished = gitIntegrationService.finishManagedSshKeyRemoval(
                integration.getId(), marker.getLockVersion());
        assertEquals(marker.getLockVersion() + 1, finished.getLockVersion());
        assertFalse(finished.hasManagedSshKeyTracking());
    }

    @Test
    void deletionFencePersistsSafeRetryableState() {
        GitIntegration integration = gitIntegration(true);

        GitIntegration pending = gitIntegrationService.beginDelete(integration.getId()).orElseThrow();
        GitIntegration reloaded = gitIntegrationRepository.findById(integration.getId()).orElseThrow();
        assertTrue(reloaded.isDeletionPending());
        assertEquals(GitTransport.HTTP, reloaded.getTransport());
        assertNull(reloaded.getSshPrivateKey());
        assertNull(reloaded.getSshKnownHosts());
        assertTrue(reloaded.hasManagedSshKeyTracking());

        GitIntegration retry = gitIntegrationService.beginDelete(integration.getId()).orElseThrow();
        gitIntegrationService.completeDelete(integration.getId(), retry.getLockVersion());
        assertFalse(gitIntegrationRepository.existsById(integration.getId()));
    }

    @Test
    void botAssignmentWinningLockPreventsDeletion() throws Exception {
        RaceData data = raceData();
        CountDownLatch assignmentReady = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch releaseAssignment = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> assignment = executor.submit(() -> tx.executeWithoutResult(status -> {
                botService.save(data.bot());
                botRepository.flush();
                assignmentReady.countDown();
                await(releaseAssignment);
            }));
            assertTrue(assignmentReady.await(5, TimeUnit.SECONDS));

            Future<?> deletion = executor.submit(() -> {
                deleteStarted.countDown();
                gitIntegrationService.beginDelete(data.integrationId());
            });
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(deletion);

            releaseAssignment.countDown();
            assignment.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertFalse(gitIntegrationRepository.findById(data.integrationId()).orElseThrow().isDeletionPending());
        } finally {
            releaseAssignment.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deletionWinningLockPreventsBotAssignment() throws Exception {
        RaceData data = raceData();
        CountDownLatch deletionReady = new CountDownLatch(1);
        CountDownLatch assignmentStarted = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> tx.executeWithoutResult(status -> {
                gitIntegrationService.beginDelete(data.integrationId()).orElseThrow();
                deletionReady.countDown();
                await(releaseDeletion);
            }));
            assertTrue(deletionReady.await(5, TimeUnit.SECONDS));

            Future<?> assignment = executor.submit(() -> {
                assignmentStarted.countDown();
                botService.save(data.bot());
            });
            assertTrue(assignmentStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(assignment);

            releaseDeletion.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> assignment.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(gitIntegrationRepository.findById(data.integrationId()).orElseThrow().isDeletionPending());
            assertFalse(botRepository.existsByGitIntegrationId(data.integrationId()));
        } finally {
            releaseDeletion.countDown();
            executor.shutdownNow();
        }
    }

    private RaceData raceData() {
        return tx.execute(status -> {
            String suffix = String.valueOf(System.nanoTime());

            SystemPrompt prompt = new SystemPrompt();
            prompt.setName("concurrency-prompt-" + suffix);
            prompt.setReviewSystemPrompt("r");
            prompt.setReviewAgentSystemPrompt("ra");
            prompt.setIssueAgentSystemPrompt("ia");
            prompt.setWriterAgentSystemPrompt("wa");
            prompt.setE2ePlannerSystemPrompt("ep");
            prompt.setE2eAuthorSystemPrompt("ea");
            prompt.setE2eRunnerSystemPrompt("er");
            prompt.setUnitTestAuthorSystemPrompt("ut");
            prompt.setReadmeSyncSystemPrompt("rs");
            prompt.setI18nCoverageSystemPrompt("i18n");
            systemPromptRepository.save(prompt);

            BotToolConfiguration tools = new BotToolConfiguration();
            tools.setName("concurrency-tools-" + suffix);
            botToolConfigurationRepository.save(tools);

            AiIntegration ai = new AiIntegration();
            ai.setName("concurrency-ai-" + suffix);
            ai.setProviderType("OPENAI");
            ai.setApiUrl("http://localhost");
            ai.setModel("test-model");
            aiIntegrationRepository.save(ai);

            GitIntegration git = new GitIntegration();
            git.setName("concurrency-git-" + suffix);
            git.setUrl("http://localhost");
            gitIntegrationRepository.saveAndFlush(git);

            Bot bot = new Bot();
            bot.setName("concurrency-bot-" + suffix);
            bot.setUsername("concurrency_bot");
            bot.setSystemPrompt(prompt);
            bot.setToolConfiguration(tools);
            bot.setAiIntegration(ai);
            bot.setGitIntegration(git);
            return new RaceData(git.getId(), bot);
        });
    }

    private GitIntegration gitIntegration(boolean managedKey) {
        return tx.execute(status -> {
            GitIntegration integration = new GitIntegration();
            integration.setName("concurrency-git-" + System.nanoTime());
            integration.setUrl("http://localhost");
            if (managedKey) {
                integration.setTransport(GitTransport.SSH);
                integration.setSshPrivateKey("encrypted-private-key");
                integration.setSshKnownHosts("known-hosts");
                integration.setSshRemoteKeyId(42L);
                integration.setSshRemoteKeyOwnerId(17L);
                integration.setSshRemoteKeyTitle("managed-key");
            }
            return gitIntegrationRepository.saveAndFlush(integration);
        });
    }

    private static void assertBlocked(Future<?> future) {
        assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record RaceData(Long integrationId, Bot bot) {
    }
}
