package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.SshEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GiteaSshSetupServiceTest {
    private static final String TITLE = "AI Git Bot: integration-7-unique";
    private static final String REMOTE = "ssh://git@gitea.example.com:2222/owner/repo.git";

    @Mock private GitIntegrationService gitIntegrationService;
    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private SshCommandService sshCommandService;
    @Mock private GiteaApiClient client;
    @Mock private GiteaApiClient replacement;
    @InjectMocks private GiteaSshSetupService service;

    private GitIntegration integration;
    private SshCommandService.HostKeyScan scan;

    @BeforeEach
    void setUp() {
        integration = new GitIntegration();
        integration.setId(7L);
        integration.setName("production");
        integration.setUrl("https://gitea.example.com");
        integration.setToken("encrypted-token");
        scan = new SshCommandService.HostKeyScan(new SshEndpoint("gitea.example.com", 2222),
                "[gitea.example.com]:2222 ssh-ed25519 AQID\n",
                List.of(new SshCommandService.HostKeyFingerprint("ssh-ed25519", "SHA256:fingerprint")), "scan");
    }

    @Test
    void preview_requiresEncryptionBeforeRemoteAccess() {
        assertThrows(IllegalStateException.class, () -> service.preview(7L));
        verifyNoInteractions(giteaClientFactory, sshCommandService);
        verify(gitIntegrationService, never()).findById(anyLong());
    }

    @Test
    void preview_isReadOnly() {
        prepareScan();
        var preview = service.preview(7L);
        assertEquals(scan, preview.hostKeys());
        assertEquals(REMOTE, preview.sshCloneUrl());
        verify(client, never()).createSshKey(anyString(), anyString());
        verify(gitIntegrationService, never()).prepareManagedSshKeyCreation(anyLong(), anyLong(), anyString());
    }

    @Test
    void setup_requiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException.class, () -> service.setup(7L, "scan", false));
        verifyNoInteractions(gitIntegrationService, client, sshCommandService);
    }

    @Test
    void setup_rejectsTamperedConfirmationBeforeGeneratingOrRemovingKeys() {
        prepareScan();
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "tampered", true));
        verify(sshCommandService, never()).generateKeyPair(anyString());
        verify(client, never()).deleteSshKey(anyLong());
    }

    @Test
    void setup_rejectsWorkingManualKey() {
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-private-key");
        prepareContext();
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "scan", true));
        verifyNoInteractions(giteaClientFactory, sshCommandService);
    }

    @Test
    void preview_rejectsNonGiteaAndMissingToken() {
        prepareContext();
        integration.setProviderType(RepositoryType.GITHUB);
        assertThrows(IllegalArgumentException.class, () -> service.preview(7L));
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("");
        assertThrows(IllegalArgumentException.class, () -> service.preview(7L));
        verifyNoInteractions(giteaClientFactory);
    }

    @Test
    void setup_commitsRecoveryMarkerBeforeRegistrationAndStoresReturnedId() {
        prepareGeneration();
        when(client.createSshKey(anyString(), eq("public"))).thenReturn(42L);
        when(gitIntegrationService.configureGeneratedSsh(eq(7L), eq("private"), eq(scan.knownHosts()),
                eq(42L), eq(17L), anyString())).thenReturn(integration);
        assertSame(integration, service.setup(7L, "scan", true));
        var order = inOrder(client, gitIntegrationService, sshCommandService);
        order.verify(sshCommandService).scanHostKeys(REMOTE);
        order.verify(client).getCurrentUserId();
        order.verify(sshCommandService).generateKeyPair(startsWith("AI Git Bot: integration-7-"));
        order.verify(gitIntegrationService).prepareManagedSshKeyCreation(eq(7L), eq(17L), anyString());
        order.verify(client).createSshKey(anyString(), eq("public"));
        order.verify(gitIntegrationService).configureGeneratedSsh(eq(7L), eq("private"), eq(scan.knownHosts()),
                eq(42L), eq(17L), anyString());
    }

    @Test
    void registrationFailure_retainsRecoveryMarker() {
        prepareGeneration();
        when(client.createSshKey(anyString(), anyString())).thenThrow(new IllegalStateException("lost response"));
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "scan", true));
        verify(gitIntegrationService).prepareManagedSshKeyCreation(eq(7L), eq(17L), anyString());
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(anyLong());
        verify(gitIntegrationService, never()).configureGeneratedSsh(anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), anyString());
    }

    @Test
    void localStorageFailure_rollsBackRegisteredKey() {
        prepareStorageFailure();
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "scan", true));
        var order = inOrder(client, gitIntegrationService);
        order.verify(client).deleteSshKey(42L);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L);
    }

    @Test
    void failedRollback_retainsRecoveryMarker() {
        prepareStorageFailure();
        doThrow(new IllegalStateException("unavailable")).when(client).deleteSshKey(42L);
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "scan", true));
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(anyLong());
    }

    @Test
    void retry_cleansCreationMarkerBeforeRegisteringAnotherKey() {
        prepareGeneration();
        integration.setSshRemoteKeyOwnerId(17L);
        integration.setSshRemoteKeyTitle(TITLE);
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(integration);
        when(client.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of(11L));
        when(client.createSshKey(anyString(), anyString())).thenReturn(42L);
        service.setup(7L, "scan", true);
        var order = inOrder(gitIntegrationService, client);
        order.verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L);
        order.verify(client).deleteSshKey(11L);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L);
        order.verify(gitIntegrationService).prepareManagedSshKeyCreation(eq(7L), eq(17L), anyString());
        order.verify(client).createSshKey(anyString(), anyString());
    }

    @Test
    void retry_failureDoesNotClearOldTrackingOrCreateAnotherKey() {
        prepareScan();
        when(client.getCurrentUserId()).thenReturn(17L);
        integration.setSshRemoteKeyId(11L);
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(integration);
        when(client.getSshKeyIds()).thenReturn(List.of(11L));
        doThrow(new IllegalStateException("unavailable")).when(client).deleteSshKey(11L);
        assertThrows(IllegalStateException.class, () -> service.setup(7L, "scan", true));
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(anyLong());
        verify(client, never()).createSshKey(anyString(), anyString());
    }

    @Test
    void cleanup_recoversAllExactTitleMatches() {
        prepareCleanup();
        integration.setSshRemoteKeyId(null);
        when(client.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of(42L, 43L));
        assertTrue(service.removeManagedKey(integration, null));
        verify(client).deleteSshKey(42L);
        verify(client).deleteSshKey(43L);
    }

    @Test
    void cleanup_titleMismatchRetainsTrackingAndDoesNotDeleteEitherKey() {
        prepareCleanup();
        when(client.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of(43L));
        when(client.getSshKeyIds()).thenReturn(List.of(42L, 43L));
        assertFalse(service.removeManagedKey(integration, null));
        verify(client, never()).deleteSshKey(anyLong());
        assertTrue(integration.hasManagedSshKeyTracking());
    }

    @Test
    void cleanup_absentKeyIsSuccess() {
        prepareCleanup();
        when(client.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of());
        when(client.getSshKeyIds()).thenReturn(List.of());
        assertTrue(service.removeManagedKey(integration, null));
        verify(client, never()).deleteSshKey(anyLong());
    }

    @Test
    void cleanup_delete404IsSuccess() {
        prepareCleanup();
        when(client.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of(42L));
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "missing", null, null, null))
                .when(client).deleteSshKey(42L);
        assertTrue(service.removeManagedKey(integration, null));
    }

    @Test
    void cleanup_wrongPrimaryOwnerCannotDeleteKeys() {
        prepareCleanup();
        when(client.getCurrentUserId()).thenReturn(18L);
        assertFalse(service.removeManagedKey(integration, null));
        verify(client, never()).getSshKeyIdsByTitle(anyString());
        verify(client, never()).deleteSshKey(anyLong());
    }

    @Test
    void cleanup_expiredTokenCanUseReplacementForSameOwner() {
        prepareCleanup();
        when(client.getCurrentUserId()).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        when(giteaClientFactory.createApiClient(integration, "new-token")).thenReturn(replacement);
        when(replacement.getCurrentUserId()).thenReturn(17L);
        when(replacement.getSshKeyIdsByTitle(TITLE)).thenReturn(List.of(42L));
        assertTrue(service.removeManagedKey(integration, "new-token"));
        verify(replacement).deleteSshKey(42L);
    }

    @Test
    void cleanup_wrongReplacementOwnerCannotDeleteKeys() {
        prepareCleanup();
        when(client.getCurrentUserId()).thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        when(giteaClientFactory.createApiClient(integration, "new-token")).thenReturn(replacement);
        when(replacement.getCurrentUserId()).thenReturn(18L);
        assertFalse(service.removeManagedKey(integration, "new-token"));
        verify(replacement, never()).deleteSshKey(anyLong());
    }

    @Test
    void cleanup_doesNotRetryNetworkFailureWithReplacementToken() {
        prepareCleanup();
        when(client.getSshKeyIdsByTitle(TITLE)).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(service.removeManagedKey(integration, "new-token"));
        verify(giteaClientFactory, never()).createApiClient(any(), anyString());
    }

    @Test
    void cleanup_ownerOnlyMarkerIsAmbiguous() {
        prepareCleanup();
        integration.setSshRemoteKeyId(null);
        integration.setSshRemoteKeyTitle(null);
        assertFalse(service.removeManagedKey(integration, null));
        verify(client, never()).deleteSshKey(anyLong());
    }

    @Test
    void cleanup_manualKeyDoesNotContactGitea() {
        assertTrue(service.removeManagedKey(integration, null));
        verifyNoInteractions(giteaClientFactory);
    }

    private void prepareContext() {
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(integration));
    }

    private void prepareScan() {
        prepareContext();
        when(giteaClientFactory.getApiClient(integration)).thenReturn(client);
        when(client.getAnySshCloneUrl()).thenReturn(REMOTE);
        when(sshCommandService.scanHostKeys(REMOTE)).thenReturn(scan);
    }

    private void prepareGeneration() {
        prepareScan();
        when(client.getCurrentUserId()).thenReturn(17L);
        when(sshCommandService.generateKeyPair(anyString())).thenReturn(new SshCommandService.SshKeyPair("private", "public"));
    }

    private void prepareStorageFailure() {
        prepareGeneration();
        when(client.createSshKey(anyString(), anyString())).thenReturn(42L);
        when(gitIntegrationService.configureGeneratedSsh(eq(7L), anyString(), anyString(), eq(42L), eq(17L), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));
    }

    private void prepareCleanup() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        integration.setSshRemoteKeyTitle(TITLE);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
    }
}
