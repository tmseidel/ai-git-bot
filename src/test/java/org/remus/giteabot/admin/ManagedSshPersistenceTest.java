package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.SshEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:managed-ssh-proof;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ManagedSshPersistenceTest {
    private static final String SENTINEL = "test-only-upstream-secret-sentinel";
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private GitIntegrationService service;
    @Autowired private GitIntegrationController controller;
    @Autowired private GitIntegrationRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private GiteaSshSetupService setupService;
    @Autowired private BotService botService;
    @Autowired private BotRepository botRepository;
    @Autowired private AiIntegrationRepository aiRepository;
    @Autowired private org.remus.giteabot.systemsettings.SystemPromptRepository promptRepository;
    @Autowired private org.remus.giteabot.systemsettings.BotToolConfigurationRepository toolsRepository;
    @MockitoBean private GiteaClientFactory factory;
    @MockitoBean private SshCommandService commands;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private EncryptionService encryption;
    private GiteaApiClient client;
    private GitIntegration saved;

    @BeforeEach
    void seed() {
        client = mock(GiteaApiClient.class);
        GitIntegration input = new GitIntegration();
        input.setName("managed-proof-" + UUID.randomUUID());
        input.setUrl("https://gitea.example.com");
        input.setToken("test-token");
        saved = service.save(input, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name-null", "name-blank", "name-long", "provider", "url-null", "url-blank",
            "url-long", "action", "username-long", "token-long"})
    void invalidRotationCannotChangeStateOrRevokeWorkingKey(String invalid) {
        makeManaged();
        GitIntegration before = readCommitted();
        GitIntegration input = readCommitted();
        input.setToken(null);
        input.setSshPrivateKey("replacement-key");
        input.setSshKnownHosts(null);
        switch (invalid) {
            case "name-null" -> input.setName(null);
            case "name-blank" -> input.setName(" ");
            case "name-long" -> input.setName("n".repeat(256));
            case "provider" -> input.setProviderType(null);
            case "url-null" -> input.setUrl(null);
            case "url-blank" -> input.setUrl(" ");
            case "url-long" -> input.setUrl("https://example.com/" + "u".repeat(256));
            case "action" -> input.setPostReviewAction(null);
            case "username-long" -> input.setUsername("u".repeat(256));
            case "token-long" -> input.setToken("t".repeat(200));
        }
        var flash = new RedirectAttributesModelMap();
        controller.save(input, input.getToken(), false, "replacement-key", null, false, flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertEquals(before, readCommitted());
        verifyNoInteractions(factory, commands, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "endpoint", "clear", "pending-cleanup"})
    void incompleteSshReplacementDoesNotRevokeKeyOrChangeCommittedState(String change) {
        makeManaged();
        if (change.equals("pending-cleanup")) {
            service.prepareManagedSshKeyRemoval(saved.getId(), readCommitted().getLockVersion());
        }
        GitIntegration before = readCommitted();
        GitIntegration input = readCommitted();
        input.setTransport(GitTransport.SSH);
        input.setToken(null);
        input.setSshPrivateKey(null);
        input.setSshKnownHosts("new-hosts");
        if (change.equals("token") || change.equals("endpoint")) {
            input.setToken("replacement-token");
        }
        if (change.equals("endpoint")) {
            input.setUrl("https://new.example.com");
        }
        var flash = new RedirectAttributesModelMap();

        controller.save(input, input.getToken(), false, null, "new-hosts", change.equals("clear"), flash);

        assertEquals(GitTransport.SSH, input.getTransport());
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertFalse(flash.getFlashAttributes().containsKey("success"));
        assertEquals(before, readCommitted());
        verifyNoInteractions(factory, commands, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSH", "HTTP"})
    void validReplacementKeepsRequestedTransportAfterHttpFirstCleanup(String transport) {
        makeManaged();
        GitIntegration input = readCommitted();
        input.setTransport(GitTransport.valueOf(transport));
        input.setToken(null);
        input.setSshPrivateKey(transport.equals("SSH") ? "replacement-key" : null);
        input.setSshKnownHosts(null);
        String storedToken = readCommitted().getToken();
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        when(client.getSshKeyIdsByTitle("tracked-title")).thenReturn(List.of(42L));
        doAnswer(call -> {
            GitIntegration pending = readCommitted();
            assertEquals(GitTransport.HTTP, pending.getTransport());
            assertNull(pending.getSshPrivateKey());
            assertTrue(pending.hasManagedSshKeyTracking());
            return null;
        }).when(client).deleteSshKey(42L);
        var flash = new RedirectAttributesModelMap();

        controller.save(input, null, false, input.getSshPrivateKey(), null, false, flash);

        assertTrue(flash.getFlashAttributes().containsKey("success"));
        assertFalse(flash.getFlashAttributes().containsKey("error"));
        GitIntegration after = readCommitted();
        assertEquals(GitTransport.valueOf(transport), after.getTransport());
        assertFalse(after.hasManagedSshKeyTracking());
        assertEquals(storedToken, after.getToken());
        if (transport.equals("SSH")) {
            assertEquals("replacement-key", service.decryptSshPrivateKey(after));
            assertEquals(MANAGED_HOSTS, after.getSshKnownHosts());
        } else {
            assertNull(after.getSshPrivateKey());
            assertNull(after.getSshKnownHosts());
        }
        verify(client).deleteSshKey(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB", "BITBUCKET"})
    void preflightHonorsProviderUrlDefaults(String provider) {
        GitIntegration input = readCommitted();
        input.setProviderType(RepositoryType.valueOf(provider));
        input.setUrl(null);
        input.setToken("replacement-token");
        service.validateSave(input, false, true);
        assertEquals(provider.equals("GITHUB") ? "https://github.com" : "https://bitbucket.org", input.getUrl());
    }

    @Test
    void registrationMarkerIsCommittedBeforeRequestAndSurvivesLostResponse(CapturedOutput output) {
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getAnySshCloneUrl()).thenReturn("git@gitea.example.com:owner/repo.git");
        when(client.getCurrentUserId()).thenReturn(17L);
        when(commands.scanHostKeys(anyString())).thenReturn(new SshCommandService.HostKeyScan(
                new SshEndpoint("gitea.example.com", 22), "hosts", List.of(), "scan"));
        when(commands.generateKeyPair(anyString())).thenReturn(new SshCommandService.SshKeyPair("private", "public"));
        when(client.createSshKey(anyString(), anyString())).thenAnswer(call -> {
            GitIntegration marker = readCommitted();
            assertEquals(GitTransport.HTTP, marker.getTransport());
            assertNull(marker.getSshPrivateKey());
            assertEquals(17L, marker.getSshRemoteKeyOwnerId());
            assertEquals(call.getArgument(0), marker.getSshRemoteKeyTitle());
            throw upstreamFailure();
        });
        var flash = new RedirectAttributesModelMap();
        controller.confirmSshSetup(saved.getId(), "scan", true, saved.getLockVersion(), flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().hasManagedSshKeyTracking());
        assertNull(readCommitted().getSshRemoteKeyId());
        assertFalse(flash.getFlashAttributes().toString().contains(SENTINEL));
        assertFalse(output.getAll().contains(SENTINEL));
    }

    @Test
    void httpStateIsCommittedBeforeDeletionAndFailedCleanupIsRetryable(CapturedOutput output) {
        makeManaged();
        String storedToken = readCommitted().getToken();
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        when(client.getSshKeyIdsByTitle("tracked-title")).thenReturn(List.of(42L));
        doAnswer(call -> {
            GitIntegration pending = readCommitted();
            assertEquals(GitTransport.HTTP, pending.getTransport());
            assertNull(pending.getSshPrivateKey());
            assertNull(pending.getSshKnownHosts());
            assertEquals(42L, pending.getSshRemoteKeyId());
            assertEquals(17L, pending.getSshRemoteKeyOwnerId());
            assertEquals("tracked-title", pending.getSshRemoteKeyTitle());
            assertEquals(storedToken, pending.getToken());
            throw upstreamFailure();
        }).when(client).deleteSshKey(42L);
        var flash = new RedirectAttributesModelMap();
        controller.delete(saved.getId(), flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().hasManagedSshKeyTracking());
        assertEquals(GitTransport.HTTP, readCommitted().getTransport());
        assertTrue(readCommitted().isDeletionPending());
        assertThrows(RuntimeException.class, () -> service.save(rawForm(), false));
        assertFalse(flash.getFlashAttributes().toString().contains(SENTINEL));
        assertFalse(output.getAll().contains(SENTINEL));
        doNothing().when(client).deleteSshKey(42L);
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "confirm"})
    void staleFormsLoseToCommittedConcurrentEditBeforeRemoteEffects(String operation) throws Exception {
        if (operation.equals("edit")) {
            makeManaged();
        }
        GitIntegration stale = rawForm();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                GitIntegration newer = rawForm();
                newer.setName("newer-" + UUID.randomUUID());
                service.save(newer, false);
            }).get(5, TimeUnit.SECONDS);
        }
        GitIntegration before = readCommitted();
        var flash = new RedirectAttributesModelMap();
        if (operation.equals("edit")) {
            stale.setTransport(GitTransport.HTTP);
            controller.save(stale, null, false, null, null, false, flash);
        } else {
            controller.confirmSshSetup(saved.getId(), "scan", true, stale.getLockVersion(), flash);
        }
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertEquals(before, readCommitted());
        verifyNoInteractions(factory, commands, client);
    }

    @Test
    void missingVersionAndDeletedIdsCannotResurrectAnIntegration() {
        GitIntegration input = rawForm();
        input.setLockVersion(null);
        assertThrows(RuntimeException.class, () -> service.save(input, false));
        GitIntegration pending = service.beginDelete(saved.getId()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> service.save(rawForm(), false));
        assertThrows(IllegalStateException.class, () -> setupService.preview(saved.getId()));
        service.completeDelete(saved.getId(), pending.getLockVersion());
        service.completeDelete(saved.getId(), pending.getLockVersion());
        assertTrue(service.beginDelete(saved.getId()).isEmpty());
        input.setLockVersion(pending.getLockVersion());
        assertThrows(IllegalArgumentException.class, () -> service.save(input, false));
        verifyNoInteractions(factory, commands, client);
    }

    @Test
    void staleCleanupAndCompensationCannotClearNewerMarker() throws Exception {
        GitIntegration old = service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "old");
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                GitIntegration cleaned = service.finishManagedSshKeyRemoval(saved.getId(), old.getLockVersion());
                service.prepareManagedSshKeyCreation(saved.getId(), cleaned.getLockVersion(), 17L, "new");
            }).get(5, TimeUnit.SECONDS);
        }
        assertThrows(RuntimeException.class, () -> service.finishManagedSshKeyRemoval(saved.getId(), old.getLockVersion()));
        assertThrows(RuntimeException.class, () -> setupService.removeManagedKey(old, null));
        assertThrows(RuntimeException.class, () -> service.withLockedVersion(saved.getId(), old.getLockVersion(), locked -> {
            fail("Stale compensation must not execute remote work");
            return null;
        }));
        assertEquals("new", readCommitted().getSshRemoteKeyTitle());
        verifyNoInteractions(factory, commands, client);
    }

    @Test
    void failedCompensationKeepsCommittedMarkerAndCanBeRetried() {
        prepareRegistration();
        when(client.createSshKey(anyString(), anyString())).thenReturn(42L);
        doThrow(new IllegalStateException("storage unavailable")).when(encryption).encrypt("private");
        doThrow(upstreamFailure()).when(client).deleteSshKey(42L);
        assertThrows(IllegalStateException.class,
                () -> setupService.setup(saved.getId(), saved.getLockVersion(), "scan", true));
        GitIntegration marker = readCommitted();
        assertTrue(marker.hasManagedSshKeyTracking());
        assertNull(marker.getSshRemoteKeyId());
        assertEquals(GitTransport.HTTP, marker.getTransport());
        assertNull(marker.getSshPrivateKey());
        assertEquals(saved.getLockVersion() + 2, marker.getLockVersion());
        assertTrue(marker.isSshCleanupVerified());
        when(client.getSshKeyIdsByTitle(marker.getSshRemoteKeyTitle())).thenReturn(List.of(42L));
        doNothing().when(client).deleteSshKey(42L);
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    @Test
    void deletionBeforePostCancelsOnlyUndispatchedMarkerAndRetryCompletes() throws Exception {
        prepareRegistration();
        CountDownLatch markerCommitted = new CountDownLatch(1);
        CountDownLatch deletionCommitted = new CountDownLatch(1);
        doAnswer(call -> {
            Object marker = call.callRealMethod();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            markerCommitted.countDown();
                            await(deletionCommitted);
                        }
                    });
            return marker;
        }).when(service).prepareManagedSshKeyCreation(eq(saved.getId()), anyLong(), eq(17L), anyString());
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> setup = executor.submit(() -> setupService.setup(saved.getId(), saved.getLockVersion(), "scan", true));
            assertTrue(markerCommitted.await(5, TimeUnit.SECONDS));
            GitIntegration pending = service.beginDelete(saved.getId()).orElseThrow();
            assertTrue(pending.hasManagedSshKeyTracking());
            deletionCommitted.countDown();
            assertThrows(ExecutionException.class, () -> setup.get(5, TimeUnit.SECONDS));
            assertTrue(readCommitted().isDeletionPending());
            assertFalse(readCommitted().hasManagedSshKeyTracking());
            controller.delete(saved.getId(), new RedirectAttributesModelMap());
            assertTrue(repository.findById(saved.getId()).isEmpty());
            verify(client, never()).createSshKey(anyString(), anyString());
            verify(client, never()).deleteSshKey(anyLong());
        } finally {
            deletionCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void undispatchedCompensationCannotClearDifferentOwnerOrNewerTitle() {
        service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "newer");
        service.beginDelete(saved.getId());
        service.cancelUndispatchedSshCreation(saved.getId(), 17L, "older");
        service.cancelUndispatchedSshCreation(saved.getId(), 18L, "newer");
        assertEquals("newer", readCommitted().getSshRemoteKeyTitle());
        assertTrue(readCommitted().isDeletionPending());
    }

    @Test
    void discoveredKeyDeletionSurvivesLocalCommitFailureAndRetriesEmptyLookup() {
        service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "discovered");
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        var visible = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(client.getSshKeyIdsByTitle("discovered")).thenAnswer(call -> visible.get() ? List.of(42L) : List.of());
        doAnswer(call -> {
            assertTrue(readCommitted().isSshCleanupVerified());
            visible.set(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("simulated local commit failure");
                        }
                    });
            return null;
        }).when(client).deleteSshKey(42L);
        var flash = new RedirectAttributesModelMap();
        controller.delete(saved.getId(), flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().hasManagedSshKeyTracking());
        assertTrue(readCommitted().isSshCleanupVerified());
        assertTrue(readCommitted().isDeletionPending());
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        assertTrue(repository.findById(saved.getId()).isEmpty());
        verify(client).deleteSshKey(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-owner", "wrong-owner", "stale"})
    void fencedDeletionCanUseOnlyCurrentSameOwnerReplacementToken(String retry, CapturedOutput output) {
        makeManaged();
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        GitIntegration fenced = readCommitted();
        assertTrue(fenced.isDeletionPending());
        GiteaApiClient replacement = mock(GiteaApiClient.class);
        when(factory.createApiClient(any(), eq(SENTINEL))).thenReturn(replacement);
        when(replacement.getCurrentUserId()).thenReturn(retry.equals("wrong-owner") ? 18L : 17L);
        when(replacement.getSshKeyIdsByTitle("tracked-title")).thenReturn(List.of(42L));
        doAnswer(call -> {
            assertEquals(fenced.getToken(), readCommitted().getToken());
            assertEquals(fenced.getUrl(), readCommitted().getUrl());
            assertTrue(readCommitted().isDeletionPending());
            assertEquals(GitTransport.HTTP, readCommitted().getTransport());
            return null;
        }).when(replacement).deleteSshKey(42L);
        clearInvocations(factory, client);
        var flash = new RedirectAttributesModelMap();
        controller.retryDelete(saved.getId(), fenced.getLockVersion() - (retry.equals("stale") ? 1 : 0), SENTINEL, flash);
        assertFalse(flash.getFlashAttributes().toString().contains(SENTINEL));
        assertFalse(output.getAll().contains(SENTINEL));
        if (retry.equals("same-owner")) {
            assertTrue(flash.getFlashAttributes().containsKey("success"));
            assertTrue(repository.findById(saved.getId()).isEmpty());
            verify(replacement).deleteSshKey(42L);
        } else {
            assertTrue(flash.getFlashAttributes().containsKey("error"));
            assertEquals(fenced, readCommitted());
            verify(replacement, never()).deleteSshKey(anyLong());
            if (retry.equals("stale")) {
                verifyNoInteractions(factory, client, replacement);
            }
        }
    }

    @Test
    void unresolvedRegistrationCannotLoseItsMarkerToDeletionRetry() {
        GitIntegration marker = service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "inflight");
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        when(client.getSshKeyIdsByTitle("inflight")).thenReturn(List.of());
        var flash = new RedirectAttributesModelMap();
        controller.delete(saved.getId(), flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().isDeletionPending());
        assertEquals(marker.getSshRemoteKeyTitle(), readCommitted().getSshRemoteKeyTitle());
        when(client.getSshKeyIdsByTitle("inflight")).thenReturn(List.of(42L));
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    @Test
    void staleManagedEntityCannotOverwriteNewerCommittedState() throws Exception {
        transaction().executeWithoutResult(status -> {
            GitIntegration stale = repository.findById(saved.getId()).orElseThrow();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    GitIntegration newer = rawForm();
                    newer.setName("newer-" + UUID.randomUUID());
                    service.save(newer, false);
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            stale.setToken(null);
            stale.setName("stale-name");
            assertThrows(RuntimeException.class, () -> service.save(stale, false));
            status.setRollbackOnly();
        });
        assertTrue(readCommitted().getName().startsWith("newer-"));
    }

    @Test
    void registrationHoldsRowUntilLocalCompletionSoDeletionCannotClearInflightMarker() throws Exception {
        prepareRegistration();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        when(client.createSshKey(anyString(), anyString())).thenAnswer(call -> {
            assertTrue(readCommitted().hasManagedSshKeyTracking());
            registered.countDown();
            await(release);
            return 42L;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<GitIntegration> setup = executor.submit(() -> setupService.setup(saved.getId(), saved.getLockVersion(), "scan", true));
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            Future<GitIntegration> deletion = executor.submit(() -> {
                deleting.countDown();
                return service.beginDelete(saved.getId()).orElseThrow();
            });
            assertTrue(deleting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(GitTransport.SSH, setup.get(5, TimeUnit.SECONDS).getTransport());
            GitIntegration pending = deletion.get(5, TimeUnit.SECONDS);
            assertTrue(pending.isDeletionPending());
            assertEquals(42L, pending.getSshRemoteKeyId());
            assertThrows(IllegalStateException.class, () -> service.completeDelete(saved.getId(), pending.getLockVersion()));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void botAssignmentAndDeletionSerializeInBothOrderings(boolean deletionFirst) throws Exception {
        Bot bot = botInput();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction().executeWithoutResult(status -> {
                if (deletionFirst) {
                    service.beginDelete(saved.getId());
                } else {
                    botService.save(bot);
                    botRepository.flush();
                }
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                if (deletionFirst) {
                    botService.save(bot);
                } else {
                    service.beginDelete(saved.getId());
                }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals(deletionFirst, readCommitted().isDeletionPending());
            assertEquals(!deletionFirst, botRepository.existsByGitIntegrationId(saved.getId()));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleWebhookSnapshotsCannotRestoreFencedAssignment() throws Exception {
        Bot snapshot = botService.save(botInput());
        GitIntegration replacementInput = new GitIntegration();
        replacementInput.setName("replacement-" + UUID.randomUUID());
        replacementInput.setUrl("https://example.com");
        GitIntegration replacement = service.save(replacementInput, false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                Bot current = botRepository.findById(snapshot.getId()).orElseThrow();
                current.setGitIntegration(replacement);
                botService.save(current);
                service.beginDelete(saved.getId());
            }).get(5, TimeUnit.SECONDS);
        }
        botService.incrementWebhookCallCount(snapshot);
        botService.recordError(snapshot, "test failure");
        Bot current = botRepository.findById(snapshot.getId()).orElseThrow();
        assertEquals(replacement.getId(), current.getGitIntegration().getId());
        assertEquals(1, current.getWebhookCallCount());
        assertEquals("test failure", current.getLastErrorMessage());
        assertFalse(botRepository.existsByGitIntegrationId(saved.getId()));
        assertTrue(readCommitted().isDeletionPending());
    }

    @Test
    void httpOnlyCleanupRetryStillCommitsANewVersion() {
        GitIntegration marker = service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "marker");
        GitIntegration pending = service.prepareManagedSshKeyRemoval(saved.getId(), marker.getLockVersion());
        assertTrue(pending.getLockVersion() > marker.getLockVersion());
        assertEquals(pending.getLockVersion(), readCommitted().getLockVersion());
        assertThrows(RuntimeException.class, () -> service.configureGeneratedSsh(saved.getId(), marker.getLockVersion(),
                "private", "hosts", 42L, 17L, "marker"));
    }

    private Bot botInput() {
        return transaction().execute(status -> {
            AiIntegration ai = new AiIntegration();
            ai.setName("race-ai-" + UUID.randomUUID());
            ai.setProviderType("OPENAI");
            ai.setApiUrl("http://localhost");
            ai.setModel("test");
            Bot result = new Bot();
            result.setName("race-bot-" + UUID.randomUUID());
            result.setUsername("race_bot");
            result.setAiIntegration(aiRepository.save(ai));
            result.setSystemPrompt(promptRepository.findAll().getFirst());
            result.setToolConfiguration(toolsRepository.findByDefaultEntryTrue().orElseThrow());
            result.setGitIntegration(saved);
            return result;
        });
    }

    private void prepareRegistration() {
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getAnySshCloneUrl()).thenReturn("git@gitea.example.com:owner/repo.git");
        when(client.getCurrentUserId()).thenReturn(17L);
        when(commands.scanHostKeys(anyString())).thenReturn(new SshCommandService.HostKeyScan(
                new SshEndpoint("gitea.example.com", 22), "hosts", List.of(), "scan"));
        when(commands.generateKeyPair(anyString())).thenReturn(new SshCommandService.SshKeyPair("private", "public"));
    }

    private GitIntegration rawForm() {
        GitIntegration input = readCommitted();
        input.setToken(null);
        input.setSshPrivateKey(null);
        input.setSshKnownHosts(null);
        return input;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static final String MANAGED_HOSTS = "[gitea.example.com]:2222 ssh-ed25519 AQID\n";

    private void makeManaged() {
        GitIntegration marker = service.prepareManagedSshKeyCreation(saved.getId(), saved.getLockVersion(), 17L, "tracked-title");
        service.configureGeneratedSsh(saved.getId(), marker.getLockVersion(), "private", MANAGED_HOSTS, 42L, 17L, "tracked-title");
    }

    private GitIntegration readCommitted() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> repository.findById(saved.getId()).orElseThrow());
    }

    private RuntimeException upstreamFailure() {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad request", null,
                SENTINEL.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
