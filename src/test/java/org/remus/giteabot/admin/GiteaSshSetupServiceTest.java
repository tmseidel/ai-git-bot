package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GiteaSshSetupServiceTest {

    private static final String KEY_TITLE = "AI Git Bot: integration-7-unique";

    @Mock
    private GitIntegrationService gitIntegrationService;

    @Mock
    private GiteaClientFactory giteaClientFactory;

    @Mock
    private SshCommandService sshCommandService;

    @Mock
    private GiteaApiClient giteaApiClient;

    @Mock
    private GiteaApiClient replacementGiteaApiClient;

    @InjectMocks
    private GiteaSshSetupService setupService;

    private GitIntegration integration;
    private SshCommandService.HostKeyScan hostKeys;

    @BeforeEach
    void setUp() {
        integration = new GitIntegration();
        integration.setId(7L);
        integration.setName("production");
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl("https://gitea.example.com");
        integration.setToken("encrypted-token");
        hostKeys = new SshCommandService.HostKeyScan(
                new SshCommandService.SshEndpoint("gitea.example.com", 2222),
                "[gitea.example.com]:2222 ssh-ed25519 AQID\n",
                List.of(new SshCommandService.HostKeyFingerprint("ssh-ed25519", "SHA256:fingerprint")),
                "confirmed-scan");
    }

    @Test
    void preview_requiresEncryptedSecretStorage() {
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> setupService.preview(7L));

        assertEquals("Automatic SSH setup requires APP_ENCRYPTION_KEY", error.getMessage());
        verify(gitIntegrationService, never()).findById(7L);
    }

    @Test
    void preview_scansEndpointFromGiteaRepository() {
        prepareIntegration();

        GiteaSshSetupService.SshSetupPreview preview = setupService.preview(7L);

        assertEquals(integration, preview.integration());
        assertEquals("ssh://git@gitea.example.com:2222/owner/repo.git", preview.sshCloneUrl());
        assertEquals(hostKeys, preview.hostKeys());
    }

    @Test
    void setup_registersKeyOnlyAfterMatchingHostKeyConfirmation() {
        prepareSetup();
        var keyPair = new SshCommandService.SshKeyPair("private-key", "ssh-ed25519 public-key gitbot");
        when(sshCommandService.generateKeyPair(anyString())).thenReturn(keyPair);
        when(giteaApiClient.createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey())))
                .thenReturn(42L);
        when(gitIntegrationService.configureGeneratedSsh(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(keyPair.privateKey()),
                org.mockito.ArgumentMatchers.eq(hostKeys.knownHosts()), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(17L), anyString()))
                .thenReturn(integration);

        assertEquals(integration, setupService.setup(7L, "confirmed-scan", true));

        InOrder order = inOrder(giteaApiClient, gitIntegrationService);
        order.verify(giteaApiClient).getCurrentUserId();
        order.verify(gitIntegrationService).prepareManagedSshKeyCreation(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(17L), anyString());
        order.verify(giteaApiClient).createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey()));
        order.verify(gitIntegrationService).configureGeneratedSsh(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("private-key"),
                org.mockito.ArgumentMatchers.eq("[gitea.example.com]:2222 ssh-ed25519 AQID\n"),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(17L), anyString());
    }

    @Test
    void setup_rejectsReplacingWorkingManualKey() {
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-private-key");
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(integration));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "confirmed-scan", true));

        assertEquals("SSH is already configured; switch to HTTP and save before replacing the key",
                error.getMessage());
        verify(giteaClientFactory, never()).getApiClient(integration);
        verify(sshCommandService, never()).generateKeyPair(anyString());
        verify(giteaApiClient, never()).createSshKey(anyString(), anyString());
        verify(giteaApiClient, never()).deleteSshKey(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void setup_createFailureLeavesRecoveryMarker() {
        prepareSetup();
        var keyPair = new SshCommandService.SshKeyPair("private-key", "ssh-ed25519 public-key gitbot");
        when(sshCommandService.generateKeyPair(anyString())).thenReturn(keyPair);
        when(giteaApiClient.createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey())))
                .thenThrow(new IllegalStateException("Gitea unavailable"));

        assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "confirmed-scan", true));

        verify(gitIntegrationService).prepareManagedSshKeyCreation(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(17L), anyString());
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(7L);
        verify(gitIntegrationService, never()).configureGeneratedSsh(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void setup_rejectsChangedHostKeysBeforeGeneratingKey() {
        prepareIntegration();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "different-scan", true));

        assertEquals("SSH host keys changed; inspect and confirm them again", error.getMessage());
        verify(sshCommandService, never()).generateKeyPair(anyString());
    }

    @Test
    void setup_removesNewRemoteKeyWhenLocalSaveFails() {
        prepareSetup();
        var keyPair = new SshCommandService.SshKeyPair("private-key", "ssh-ed25519 public-key gitbot");
        when(sshCommandService.generateKeyPair(anyString())).thenReturn(keyPair);
        when(giteaApiClient.createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey())))
                .thenReturn(42L);
        when(gitIntegrationService.configureGeneratedSsh(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(keyPair.privateKey()),
                org.mockito.ArgumentMatchers.eq(hostKeys.knownHosts()), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(17L), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "confirmed-scan", true));

        verify(giteaApiClient).deleteSshKey(42L);
        verify(gitIntegrationService).finishManagedSshKeyRemoval(7L);
    }

    @Test
    void setup_keepsNewKeyTrackedWhenRollbackDeletionFails() {
        prepareSetup();
        var keyPair = new SshCommandService.SshKeyPair("private-key", "ssh-ed25519 public-key gitbot");
        when(sshCommandService.generateKeyPair(anyString())).thenReturn(keyPair);
        when(giteaApiClient.createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey())))
                .thenReturn(42L);
        when(gitIntegrationService.configureGeneratedSsh(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(keyPair.privateKey()),
                org.mockito.ArgumentMatchers.eq(hostKeys.knownHosts()), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(17L), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("Gitea unavailable")).when(giteaApiClient).deleteSshKey(42L);

        assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "confirmed-scan", true));

        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(7L);
    }

    @Test
    void setup_keepsCleanupRetryableWhenOldRemoteKeyCannotBeRemoved() {
        integration.setSshRemoteKeyId(11L);
        prepareSetup();
        var keyPair = new SshCommandService.SshKeyPair("private-key", "ssh-ed25519 public-key gitbot");
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(integration);
        when(giteaApiClient.getSshKeyIds()).thenReturn(List.of(11L));
        doThrow(new IllegalStateException("Gitea unavailable")).when(giteaApiClient).deleteSshKey(11L);

        assertThrows(IllegalStateException.class,
                () -> setupService.setup(7L, "confirmed-scan", true));

        verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L);
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(7L);
        verify(giteaApiClient, never()).createSshKey(anyString(), org.mockito.ArgumentMatchers.eq(keyPair.publicKey()));
        verify(gitIntegrationService, never()).configureGeneratedSsh(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("private-key"),
                org.mockito.ArgumentMatchers.eq(hostKeys.knownHosts()), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(17L), anyString());
    }

    @Test
    void removeManagedKey_usesReplacementTokenOnlyForStoredOwner() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .when(giteaApiClient).getCurrentUserId();
        when(giteaClientFactory.createApiClient(integration, "replacement-token"))
                .thenReturn(replacementGiteaApiClient);
        when(replacementGiteaApiClient.getCurrentUserId()).thenReturn(17L);
        when(replacementGiteaApiClient.getSshKeyIds()).thenReturn(List.of(42L));
        assertTrue(setupService.removeManagedKey(integration, "replacement-token"));

        verify(replacementGiteaApiClient).deleteSshKey(42L);
    }

    @Test
    void removeManagedKey_recoversIdFromPersistedCreationMarker() {
        integration.setSshRemoteKeyOwnerId(17L);
        integration.setSshRemoteKeyTitle(KEY_TITLE);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getCurrentUserId()).thenReturn(17L);
        when(giteaApiClient.getSshKeyIdsByTitle(KEY_TITLE)).thenReturn(List.of(42L));

        assertTrue(setupService.removeManagedKey(integration));

        verify(giteaApiClient).deleteSshKey(42L);
    }

    @Test
    void removeManagedKey_rejectsReplacementTokenForDifferentOwner() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                .when(giteaApiClient).getCurrentUserId();
        when(giteaClientFactory.createApiClient(integration, "replacement-token"))
                .thenReturn(replacementGiteaApiClient);
        when(replacementGiteaApiClient.getCurrentUserId()).thenReturn(18L);

        assertFalse(setupService.removeManagedKey(integration, "replacement-token"));

        verify(replacementGiteaApiClient, never()).deleteSshKey(42L);
    }

    @Test
    void removeManagedKey_doesNotDeleteUnownedTrackedId() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getCurrentUserId()).thenReturn(17L);
        when(giteaApiClient.getSshKeyIds()).thenReturn(List.of());

        assertTrue(setupService.removeManagedKey(integration));

        verify(giteaApiClient, never()).deleteSshKey(42L);
    }

    @Test
    void removeManagedKey_doesNotTrustTrackedIdWhenTitleDoesNotMatch() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        integration.setSshRemoteKeyTitle(KEY_TITLE);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getCurrentUserId()).thenReturn(17L);
        when(giteaApiClient.getSshKeyIdsByTitle(KEY_TITLE)).thenReturn(List.of(43L));
        when(giteaApiClient.getSshKeyIds()).thenReturn(List.of(42L, 43L));

        assertFalse(setupService.removeManagedKey(integration));

        verify(giteaApiClient, never()).deleteSshKey(42L);
        verify(giteaApiClient, never()).deleteSshKey(43L);
    }

    @Test
    void removeManagedKey_rejectsPrimaryTokenForDifferentOwner() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getCurrentUserId()).thenReturn(18L);

        assertFalse(setupService.removeManagedKey(integration));

        verify(giteaApiClient, never()).getSshKeyIds();
        verify(giteaApiClient, never()).deleteSshKey(42L);
    }

    @Test
    void removeManagedKey_usesReplacementTokenAfterPrimaryOwnerMismatch() {
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getCurrentUserId()).thenReturn(18L);
        when(giteaClientFactory.createApiClient(integration, "replacement-token"))
                .thenReturn(replacementGiteaApiClient);
        when(replacementGiteaApiClient.getCurrentUserId()).thenReturn(17L);
        when(replacementGiteaApiClient.getSshKeyIds()).thenReturn(List.of(42L));

        assertTrue(setupService.removeManagedKey(integration, "replacement-token"));

        verify(replacementGiteaApiClient).deleteSshKey(42L);
    }

    private void prepareIntegration() {
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(integration));
        when(giteaClientFactory.getApiClient(integration)).thenReturn(giteaApiClient);
        when(giteaApiClient.getAnySshCloneUrl())
                .thenReturn("ssh://git@gitea.example.com:2222/owner/repo.git");
        when(sshCommandService.scanHostKeys("ssh://git@gitea.example.com:2222/owner/repo.git"))
                .thenReturn(hostKeys);
    }

    private void prepareSetup() {
        prepareIntegration();
        when(giteaApiClient.getCurrentUserId()).thenReturn(17L);
    }
}
