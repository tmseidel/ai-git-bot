package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(GitIntegrationController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class GitIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GitIntegrationService gitIntegrationService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void newForm_showsProviderTypes() throws Exception {
        mockMvc.perform(get("/git-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/form"))
                .andExpect(content().string(containsString("GITEA")))
                .andExpect(content().string(containsString("GITHUB")))
                .andExpect(content().string(containsString("GITLAB")))
                .andExpect(content().string(containsString("BITBUCKET")));
    }

    @Test
    void newForm_showsGiteaTransportAndSshCredentialFields() throws Exception {
        mockMvc.perform(get("/git-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"transport\"")))
                .andExpect(content().string(containsString("value=\"HTTP\"")))
                .andExpect(content().string(containsString("value=\"SSH\"")))
                .andExpect(content().string(containsString("id=\"sshPrivateKey\"")))
                .andExpect(content().string(containsString("id=\"sshKnownHosts\"")));
    }

    @Test
    void editForm_showsClearButton() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("Existing");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setToken("encrypted-token");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/form"))
                .andExpect(content().string(containsString("id=\"clearTokenBtn\"")))
                .andExpect(content().string(containsString("id=\"clearToken\"")))
                .andExpect(content().string(containsString("id=\"tokenClearPendingHint\"")));
    }

    @Test
    void editForm_doesNotRenderStoredPrivateKey() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("Existing SSH");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"clearSshCredentialsBtn\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("encrypted-private-key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("stored-host-key"))));
    }

    @Test
    void save_newIntegrationDelegatesToService() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("token", "gitea-token")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        "My Gitea".equals(integration.getName())
                                && RepositoryType.GITEA.equals(integration.getProviderType())
                                && "https://gitea.example.com".equals(integration.getUrl())
                                && "gitea-token".equals(integration.getToken())
                                && PostReviewAction.NONE.equals(integration.getPostReviewAction())
                ),
                eq(false), eq(false));
    }

    @Test
    void save_blankTokenForwardsClearFlag() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("token", "")
                        .param("clearToken", "true")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        "".equals(integration.getToken()) || integration.getToken() == null),
                eq(true), eq(false));
    }

    @Test
    void save_sshIntegrationForwardsOneWayCredentials() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("token", "gitea-token")
                        .param("sshPrivateKey", "pasted-private-key")
                        .param("sshKnownHosts", "gitea.example.com ssh-ed25519 host-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        GitTransport.SSH.equals(integration.getTransport())
                                && "pasted-private-key".equals(integration.getSshPrivateKey())
                                && "gitea.example.com ssh-ed25519 host-key".equals(integration.getSshKnownHosts())
                ),
                eq(false), eq(false));
    }

    @Test
    void save_keyOnlyRotation_preservesStoredKnownHosts() throws Exception {
        GitIntegrationRepository repository = mock(GitIntegrationRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        GitIntegrationService service = new GitIntegrationService(repository, encryption);
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setToken("stored-token");
        existing.setSshPrivateKey("stored-private-key");
        existing.setSshKnownHosts("gitea.example.com ssh-ed25519 trusted-host-key");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(encryption.isEncryptionEnabled()).thenReturn(true);
        when(encryption.encrypt("replacement-private-key")).thenReturn("encrypted-replacement-key");
        // Exercise the real credential merge behind the MVC service mock.
        when(gitIntegrationService.save(any(), anyBoolean(), anyBoolean())).thenAnswer(invocation ->
                service.save(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("token", "")
                        .param("sshPrivateKey", "replacement-private-key")
                        .param("sshKnownHosts", "")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"))
                .andExpect(flash().attributeExists("success"))
                .andExpect(flash().attributeCount(1));

        verify(repository).save(argThat(integration ->
                GitTransport.SSH.equals(integration.getTransport())
                        && "encrypted-replacement-key".equals(integration.getSshPrivateKey())
                        && "gitea.example.com ssh-ed25519 trusted-host-key".equals(integration.getSshKnownHosts())
                        && "stored-token".equals(integration.getToken())));
    }

    @Test
    void save_clearSshCredentialsForwardsClearFlag() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("clearSshCredentials", "true")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        GitTransport.HTTP.equals(integration.getTransport())
                                && integration.getSshPrivateKey() == null
                                && integration.getSshKnownHosts() == null
                ),
                eq(false), eq(true));
    }

    @Test
    void save_httpTransport_clearsSubmittedSshCredentials() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "HTTP")
                        .param("sshPrivateKey", "pasted-private-key")
                        .param("sshKnownHosts", "gitea.example.com ssh-ed25519 host-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        GitTransport.HTTP.equals(integration.getTransport())
                                && integration.getSshPrivateKey() == null
                                && integration.getSshKnownHosts() == null
                ),
                eq(false), eq(false));
    }
}
