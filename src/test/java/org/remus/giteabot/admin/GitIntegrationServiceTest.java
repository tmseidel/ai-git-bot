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

    @Mock
    private GitIntegrationRepository gitIntegrationRepository;

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
        existing.setToken("stored-encrypted-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false, false);

        assertEquals("stored-encrypted-private-key", result.getSshPrivateKey());
        assertEquals("stored-host-key", result.getSshKnownHosts());
        assertEquals("stored-encrypted-token", result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
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
    void save_replacingPrivateKeyWithoutKnownHosts_keepsStoredTrust() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl("https://gitea.example.com");
        integration.setTransport(GitTransport.SSH);
        integration.setToken("");
        integration.setSshPrivateKey("new-private-key");
        integration.setSshKnownHosts("");
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("stored-encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        existing.setToken("stored-encrypted-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("new-private-key")).thenReturn("encrypted-new-private-key");
        when(gitIntegrationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false, false);

        assertEquals(GitTransport.SSH, result.getTransport());
        assertEquals("encrypted-new-private-key", result.getSshPrivateKey());
        assertEquals("stored-host-key", result.getSshKnownHosts());
        assertEquals("stored-encrypted-token", result.getToken());
        verify(encryptionService).encrypt("new-private-key");
        verify(encryptionService, never()).encrypt("stored-encrypted-token");
    }

    @Test
    void save_keyRotationCannotReuseClearedOrMissingKnownHosts() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("new-private-key");
        GitIntegration existing = new GitIntegration();
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("stored-encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        existing.setToken("stored-encrypted-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false, true));
        assertEquals("stored-host-key", existing.getSshKnownHosts());
        assertEquals("stored-encrypted-private-key", existing.getSshPrivateKey());

        existing.setSshKnownHosts(null);
        assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false, false));
        assertEquals("stored-encrypted-private-key", existing.getSshPrivateKey());
        verify(encryptionService, never()).encrypt(anyString());
        verify(gitIntegrationRepository, never()).save(any());
    }

    @Test
    void save_changedSshEndpoint_requiresNewTrustAndKeepsPrivateKey() {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setUrl("https://old.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setToken("stored-token");
        existing.setSshPrivateKey("stored-encrypted-key");
        existing.setSshKnownHosts("old-host-key");
        GitIntegration input = new GitIntegration();
        input.setId(7L);
        input.setUrl("https://new.example.com");
        input.setTransport(GitTransport.SSH);
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(input, false, false));
        input.setToken("new-token");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(input, false, false));
        assertTrue(error.getMessage().contains("known_hosts"));
        assertEquals("https://old.example.com", existing.getUrl());
        assertEquals(GitTransport.SSH, existing.getTransport());
        assertEquals("stored-encrypted-key", existing.getSshPrivateKey());
        assertEquals("old-host-key", existing.getSshKnownHosts());
        verify(gitIntegrationRepository, never()).save(any());

        input.setSshKnownHosts("new.example.com ssh-ed25519 verified-key");
        when(encryptionService.encrypt("new-token")).thenReturn("encrypted-new-token");
        when(gitIntegrationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GitIntegration result = gitIntegrationService.save(input, false, false);

        assertEquals(GitTransport.SSH, result.getTransport());
        assertEquals("https://new.example.com", result.getUrl());
        assertEquals("stored-encrypted-key", result.getSshPrivateKey());
        assertEquals(input.getSshKnownHosts(), result.getSshKnownHosts());
        verify(encryptionService, never()).encrypt("stored-encrypted-key");
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
    void save_nonGiteaProvider_rejectsSshTransport() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITHUB);
        integration.setTransport(GitTransport.SSH);
        integration.setToken("plain-token");
        integration.setSshPrivateKey("plain-private-key");
        integration.setSshKnownHosts("github.com ssh-ed25519 host-key");
        when(encryptionService.isEncryptionEnabled()).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> gitIntegrationService.save(integration, false, false));

        assertEquals("SSH transport is currently supported for Gitea integrations only", error.getMessage());
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
    void decryptSshPrivateKey_nullKey_returnsNull() {
        GitIntegration integration = new GitIntegration();
        integration.setSshPrivateKey(null);

        assertNull(gitIntegrationService.decryptSshPrivateKey(integration));
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void deleteById_delegatesToRepository() {
        gitIntegrationService.deleteById(1L);

        verify(gitIntegrationRepository).deleteById(1L);
    }

    @Test
    void entityToString_redactsSecrets() {
        GitIntegration integration = new GitIntegration();
        integration.setToken("secret-token");
        integration.setSshPrivateKey("secret-private-key");
        integration.setSshKnownHosts("secret-host-key");

        String toString = integration.toString();

        assertFalse(toString.contains("secret-token"));
        assertFalse(toString.contains("secret-private-key"));
        assertFalse(toString.contains("secret-host-key"));
    }
}
