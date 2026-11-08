package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.GiteaPlatform;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryPlatformFactoryTest {

    @Mock
    private GitIntegrationService integrationService;
    @Mock
    private RepositoryProviderRegistry providerRegistry;
    @Mock
    private RepositoryProviderMetadata provider;
    @Mock
    private RestClient restClient;

    private RepositoryPlatformFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RepositoryPlatformFactory(integrationService, providerRegistry, Duration.ofMinutes(5));
        when(providerRegistry.getProvider(RepositoryType.GITEA)).thenReturn(provider);
        lenient().when(provider.createHttpCredentials(any(), anyString())).thenAnswer(invocation ->
                new HttpCredentials(null, invocation.getArgument(1)));
        when(provider.createPlatform(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            GitIntegration integration = invocation.getArgument(0);
            return new GiteaPlatform(integration.getUrl(), integration.getUrl(),
                    invocation.getArgument(2), invocation.getArgument(3), new GiteaApiClient(restClient),
                    integration.getSshKnownHosts(), invocation.getArgument(4));
        });
    }

    @Test
    void platformsKeepTransportAndSshCredentialsPerIntegration() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        GitIntegration http = integration(2L, "HTTP", GitTransport.HTTP);
        when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        when(integrationService.decryptToken(http)).thenReturn("http-token");
        when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        RepositoryPlatform sshPlatform = factory.getPlatform(ssh);
        RepositoryPlatform httpPlatform = factory.getPlatform(http);

        assertThat(sshPlatform.getTransport()).isEqualTo(GitTransport.SSH);
        assertThat(sshPlatform.getCredentials()).isEqualTo(
                new SshCredentials("plain-private-key", "gitea.example.com ssh-ed25519 host-key"));
        assertThat(httpPlatform.getTransport()).isEqualTo(GitTransport.HTTP);
        assertThat(httpPlatform.getCredentials()).isEqualTo(new HttpCredentials(null, "http-token"));
        verify(integrationService, never()).decryptSshPrivateKey(http);
    }

    @Test
    void credentialsToString_redactsSecrets() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        GitIntegration http = integration(2L, "HTTP", GitTransport.HTTP);
        when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        when(integrationService.decryptToken(http)).thenReturn("http-token");
        when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        String sshToString = factory.getPlatform(ssh).getCredentials().toString();
        String httpToString = factory.getPlatform(http).getCredentials().toString();

        assertThat(sshToString)
                .doesNotContain("plain-private-key")
                .doesNotContain("host-key");
        assertThat(httpToString).doesNotContain("http-token");
    }

    @Test
    void platformsAreCachedAndDecryptTheSshKeyOnlyWhenRead() {
        GitIntegration integration = integration(1L, "SSH", GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        when(integrationService.decryptToken(integration)).thenReturn("token");
        when(integrationService.decryptSshPrivateKey(integration)).thenReturn("private-key");

        RepositoryPlatform first = factory.getPlatform(integration);
        RepositoryPlatform second = factory.getPlatform(integration);

        assertThat(first).isSameAs(second);
        verify(integrationService, never()).decryptSshPrivateKey(integration);

        first.getCredentials();
        first.getCredentials();

        verify(integrationService, times(2)).decryptSshPrivateKey(integration);
    }

    @Test
    void sshPlatformsAreRebuiltWhenTheIntegrationChanges() {
        GitIntegration integration = integration(1L, "SSH", GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 old-key");
        when(integrationService.decryptToken(integration)).thenReturn("token");
        when(integrationService.decryptSshPrivateKey(integration)).thenReturn("private-key");

        RepositoryPlatform before = factory.getPlatform(integration);
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 new-key");
        integration.setUpdatedAt(integration.getUpdatedAt().plusSeconds(1));
        RepositoryPlatform after = factory.getPlatform(integration);

        assertThat(after).isNotSameAs(before);
        assertThat(after.getCredentials())
                .isEqualTo(new SshCredentials("private-key", "gitea.example.com ssh-ed25519 new-key"));
    }

    @Test
    void theTokenIsDecryptedOncePerBuildNotPerCredentialsRead() {
        GitIntegration integration = integration(1L, "HTTP", GitTransport.HTTP);
        when(integrationService.decryptToken(integration)).thenReturn("token");

        RepositoryPlatform platform = factory.getPlatform(integration);
        platform.getCredentials();
        platform.getCredentials();
        factory.getPlatform(integration).getCredentials();

        assertThat(platform.getCredentials()).isEqualTo(new HttpCredentials(null, "token"));
        verify(integrationService, times(1)).decryptToken(integration);
    }

    @Test
    void replacementTokenClient_isUncachedAndDoesNotDecryptSecrets() {
        GitIntegration integration = integration(7L, "SSH", GitTransport.SSH);

        var first = factory.createApiClient(integration, "new-token");
        var second = factory.createApiClient(integration, "new-token");

        assertThat(first).isNotSameAs(second);
        ArgumentCaptor<HttpCredentials> httpCredentials = ArgumentCaptor.forClass(HttpCredentials.class);
        verify(provider, times(2)).createPlatform(eq(integration), eq("new-token"), httpCredentials.capture(), isNull(), any());
        assertThat(httpCredentials.getValue()).isEqualTo(new HttpCredentials(null, "new-token"));
        verify(integrationService, never()).decryptToken(any());
        verify(integrationService, never()).decryptSshPrivateKey(any());
    }

    private GitIntegration integration(long id, String name, GitTransport transport) {
        GitIntegration integration = new GitIntegration();
        integration.setId(id);
        integration.setName(name);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl("https://gitea.example.com/" + name.toLowerCase());
        integration.setTransport(transport);
        integration.setUpdatedAt(Instant.parse("2026-08-31T12:00:00Z"));
        return integration;
    }
}
