package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitIntegrationServiceTest {

    private static final String REMOTE_KEY_TITLE = "AI Git Bot: integration-7-unique";

    @Mock
    private GitIntegrationRepository gitIntegrationRepository;

    @Mock
    private BotRepository botRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private GitIntegrationService gitIntegrationService;

    @Test
    void save_encryptsToken() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("plain-token");
        when(encryptionService.encrypt("plain-token")).thenReturn("encrypted-value");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("encrypted-value", result.getToken());
        verify(encryptionService).encrypt("plain-token");
    }

    @Test
    void save_blankTokenOnUpdate_keepsStoredTokenWithoutReEncrypting() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("");
        GitIntegration existing = new GitIntegration();
        existing.setToken("stored-encrypted-token");
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("stored-encrypted-token", result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_clearToken_removesStoredToken() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, true);

        assertNull(result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_nullToken_staysNull() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken(null);
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertNull(result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_duplicateName_rejectsBeforePersistence() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setName("Duplicate");
        integration.setProviderType(RepositoryType.GITEA);
        when(gitIntegrationRepository.existsByNameAndIdNot("Duplicate", 7L)).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false));

        assertEquals("A Git Integration with this name already exists", error.getMessage());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_sshTransport_encryptsPrivateKey() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.SSH);
        integration.setToken("plain-token");
        integration.setSshPrivateKey("plain-private-key");
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(encryptionService.encrypt("plain-token")).thenReturn("encrypted-token");
        when(encryptionService.encrypt("plain-private-key")).thenReturn("encrypted-private-key");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false, false);

        assertEquals("encrypted-private-key", result.getSshPrivateKey());
        assertEquals("gitea.example.com ssh-ed25519 host-key", result.getSshKnownHosts());
        verify(encryptionService).encrypt("plain-private-key");
    }

    @Test
    void save_httpTransport_ignoresSubmittedSshCredentials() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.HTTP);
        integration.setSshPrivateKey("hidden-private-key");
        integration.setSshKnownHosts("hidden-host-key");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false, false);

        assertNull(result.getSshPrivateKey());
        assertNull(result.getSshKnownHosts());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_blankSshFieldsOnUpdate_keepsStoredValuesWithoutReEncrypting() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("");
        integration.setSshKnownHosts("");
        GitIntegration existing = new GitIntegration();
        existing.setSshPrivateKey("stored-encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        existing.setSshRemoteKeyId(42L);
        existing.setSshRemoteKeyOwnerId(17L);
        existing.setSshRemoteKeyTitle(REMOTE_KEY_TITLE);
        existing.setToken("stored-encrypted-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false, false);

        assertEquals("stored-encrypted-private-key", result.getSshPrivateKey());
        assertEquals("stored-host-key", result.getSshKnownHosts());
        assertEquals(42L, result.getSshRemoteKeyId());
        assertEquals(17L, result.getSshRemoteKeyOwnerId());
        assertEquals(REMOTE_KEY_TITLE, result.getSshRemoteKeyTitle());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void managedKeyRemoval_persistsSafeRetryableStateThenClearsId() {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        existing.setSshRemoteKeyId(42L);
        existing.setSshRemoteKeyOwnerId(17L);
        existing.setSshRemoteKeyTitle(REMOTE_KEY_TITLE);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.saveAndFlush(existing)).thenReturn(existing);

        GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(7L);

        assertEquals(GitTransport.HTTP, pending.getTransport());
        assertNull(pending.getSshPrivateKey());
        assertNull(pending.getSshKnownHosts());
        assertEquals(42L, pending.getSshRemoteKeyId());
        assertEquals(17L, pending.getSshRemoteKeyOwnerId());
        assertEquals(REMOTE_KEY_TITLE, pending.getSshRemoteKeyTitle());

        GitIntegration finished = gitIntegrationService.finishManagedSshKeyRemoval(7L);

        assertNull(finished.getSshRemoteKeyId());
        assertNull(finished.getSshRemoteKeyOwnerId());
        assertNull(finished.getSshRemoteKeyTitle());
        verify(gitIntegrationRepository, times(2)).saveAndFlush(existing);
    }

    @Test
    void configureGeneratedSsh_encryptsAndTracksRegisteredKey() {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(encryptionService.encrypt("private-key")).thenReturn("encrypted-private-key");
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.configureGeneratedSsh(
                7L, "private-key", "gitea.example.com ssh-ed25519 host-key", 42L, 17L,
                REMOTE_KEY_TITLE);

        assertEquals(GitTransport.SSH, result.getTransport());
        assertEquals("encrypted-private-key", result.getSshPrivateKey());
        assertEquals("gitea.example.com ssh-ed25519 host-key", result.getSshKnownHosts());
        assertEquals(42L, result.getSshRemoteKeyId());
        assertEquals(17L, result.getSshRemoteKeyOwnerId());
        assertEquals(REMOTE_KEY_TITLE, result.getSshRemoteKeyTitle());
    }

    @Test
    void managedKeyCreation_persistsOwnerMarkerBeforeRemoteId() {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.saveAndFlush(existing)).thenReturn(existing);

        GitIntegration marker = gitIntegrationService.prepareManagedSshKeyCreation(
                7L, 17L, REMOTE_KEY_TITLE);

        assertNull(marker.getSshRemoteKeyId());
        assertEquals(17L, marker.getSshRemoteKeyOwnerId());
        assertEquals(REMOTE_KEY_TITLE, marker.getSshRemoteKeyTitle());
    }

    @Test
    void configureGeneratedSsh_requiresEncryption() {
        when(encryptionService.isEncryptionEnabled()).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gitIntegrationService.configureGeneratedSsh(
                        7L, "private-key", "host-key", 42L, 17L, REMOTE_KEY_TITLE));

        assertEquals("Automatic SSH setup requires APP_ENCRYPTION_KEY", error.getMessage());
        verify(gitIntegrationRepository, never()).findById(anyLong());
    }

    @Test
    void save_sshTransportWithoutCredentials_rejectsIntegration() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.SSH);
        integration.setToken("plain-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false, false));

        assertEquals("SSH private key and known_hosts are required for SSH transport", error.getMessage());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_changedEndpointRequiresNewToken() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl("https://new-gitea.example.com");
        GitIntegration existing = new GitIntegration();
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://old-gitea.example.com");
        existing.setToken("stored-token");
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false));

        assertEquals("A new API token is required when changing the provider or URL", error.getMessage());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_sshTransportWithoutEncryption_rejectsPrivateKey() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.SSH);
        integration.setToken("plain-token");
        integration.setSshPrivateKey("plain-private-key");
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        when(encryptionService.isEncryptionEnabled()).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gitIntegrationService.save(integration, false, false));

        assertEquals("SSH private keys require APP_ENCRYPTION_KEY", error.getMessage());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_sshTransportWithoutApiToken_rejectsIntegration() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("plain-private-key");
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false, false));

        assertEquals("API token is required for SSH transport", error.getMessage());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_githubProvider_setsDefaultUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITHUB);
        integration.setToken("gh-token");
        when(encryptionService.encrypt("gh-token")).thenReturn("encrypted");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("https://github.com", result.getUrl());
    }

    @Test
    void save_bitbucketProvider_setsDefaultUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.BITBUCKET);
        integration.setToken("bb-token");
        when(encryptionService.encrypt("bb-token")).thenReturn("encrypted");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("https://bitbucket.org", result.getUrl());
    }

    @Test
    void decryptToken_callsDecrypt() {
        GitIntegration integration = new GitIntegration();
        integration.setToken("encrypted-value");
        when(encryptionService.decrypt("encrypted-value")).thenReturn("plain-token");

        String result = gitIntegrationService.decryptToken(integration);

        assertEquals("plain-token", result);
        verify(encryptionService).decrypt("encrypted-value");
    }

    @Test
    void decryptToken_nullToken_returnsNull() {
        GitIntegration integration = new GitIntegration();
        integration.setToken(null);

        String result = gitIntegrationService.decryptToken(integration);

        assertNull(result);
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void decryptSshPrivateKey_callsDecrypt() {
        GitIntegration integration = new GitIntegration();
        integration.setSshPrivateKey("encrypted-private-key");
        when(encryptionService.decrypt("encrypted-private-key")).thenReturn("plain-private-key");

        assertEquals("plain-private-key", gitIntegrationService.decryptSshPrivateKey(integration));
        verify(encryptionService).decrypt("encrypted-private-key");
    }

    @Test
    void deleteById_delegatesToRepository() {
        gitIntegrationService.deleteById(1L);

        verify(botRepository).existsByGitIntegrationId(1L);
        verify(gitIntegrationRepository).deleteById(1L);
    }

    @Test
    void deleteById_rejectsIntegrationUsedByBot() {
        when(botRepository.existsByGitIntegrationId(1L)).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gitIntegrationService.deleteById(1L));

        assertEquals("Git Integration is still used by a bot", error.getMessage());
        verify(gitIntegrationRepository, never()).deleteById(1L);
    }
}
