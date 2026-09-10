package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GiteaClientFactoryTest {

    @Mock
    private GitIntegrationService integrationService;
    @Mock
    private RepositoryProviderRegistry providerRegistry;
    @Mock
    private RepositoryProviderMetadata provider;
    @Mock
    private RestClient restClient;

    @InjectMocks
    private GiteaClientFactory factory;

    @BeforeEach
    void setUp() {
        when(providerRegistry.getProvider(RepositoryType.GITEA)).thenReturn(provider);
        when(provider.buildRestClient(any(), anyString())).thenReturn(restClient);
        when(provider.createCredentials(any(), anyString())).thenAnswer(invocation -> {
            GitIntegration integration = invocation.getArgument(0);
            String token = invocation.getArgument(1);
            return RepositoryCredentials.of(integration.getUrl(), integration.getUrl(), token);
        });
        when(provider.createClient(any(), any())).thenAnswer(invocation ->
                new GiteaApiClient(restClient, invocation.getArgument(1)));
    }

    @Test
    void clientsKeepTransportAndSshCredentialsPerIntegration() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        GitIntegration http = integration(2L, "HTTP", GitTransport.HTTP);
        when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        when(integrationService.decryptToken(http)).thenReturn("http-token");
        when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        var sshCredentials = factory.getApiClient(ssh).getCredentials();
        var httpCredentials = factory.getApiClient(http).getCredentials();

        assertThat(sshCredentials.usesSsh()).isTrue();
        assertThat(sshCredentials.sshPrivateKey()).isEqualTo("plain-private-key");
        assertThat(sshCredentials.sshKnownHosts()).isEqualTo("gitea.example.com ssh-ed25519 host-key");
        assertThat(httpCredentials.usesSsh()).isFalse();
        assertThat(httpCredentials.sshPrivateKey()).isNull();
        verify(integrationService, never()).decryptSshPrivateKey(http);
    }

    @Test
    void credentialsToString_redactsSecrets() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        String toString = factory.getApiClient(ssh).getCredentials().toString();

        assertThat(toString)
                .doesNotContain("ssh-token")
                .doesNotContain("plain-private-key")
                .doesNotContain("host-key");
    }

    @Test
    void sshClientsAreNotCached() {
        GitIntegration integration = integration(1L, "SSH", GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        when(integrationService.decryptToken(integration)).thenReturn("token");
        when(integrationService.decryptSshPrivateKey(integration)).thenReturn("private-key");

        assertThat(factory.getApiClient(integration)).isNotSameAs(factory.getApiClient(integration));

        verify(integrationService, times(2)).decryptSshPrivateKey(integration);
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
