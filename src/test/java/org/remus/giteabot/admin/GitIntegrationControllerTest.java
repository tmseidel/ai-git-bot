package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.List;
import org.remus.giteabot.repository.SshEndpoint;

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
@ExtendWith(OutputCaptureExtension.class)
class GitIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GitIntegrationService gitIntegrationService;

    @MockitoBean
    private GiteaSshSetupService giteaSshSetupService;

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
        GitIntegrationService service = new GitIntegrationService(repository, encryption, mock(BotRepository.class));
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

    @Test
    void preview_rendersConfirmationWithoutStoredSecrets() throws Exception {
        GitIntegration integration = managedIntegration();
        var scan = new SshCommandService.HostKeyScan(new SshEndpoint("gitea.example.com", 22),
                "hosts", List.of(new SshCommandService.HostKeyFingerprint("ssh-ed25519", "SHA256:trusted")), "scan");
        when(giteaSshSetupService.preview(7L)).thenReturn(new GiteaSshSetupService.SshSetupPreview(
                integration, "git@gitea.example.com:owner/repo.git", scan));
        mockMvc.perform(get("/git-integrations/7/ssh/setup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("git-integrations/ssh-setup"))
                .andExpect(content().string(containsString("SHA256:trusted")))
                .andExpect(content().string(containsString("name=\"confirmation\" value=\"scan\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("encrypted-key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("stored-token"))));
    }

    @Test
    void confirm_forwardsExplicitConsentAndRequiresCsrf() throws Exception {
        mockMvc.perform(post("/git-integrations/7/ssh/setup").with(user("admin").roles("ADMIN"))
                        .param("confirmation", "scan").param("confirmed", "true"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/git-integrations/7/ssh/setup").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("confirmation", "scan").param("confirmed", "true"))
                .andExpect(redirectedUrl("/git-integrations/7/edit")).andExpect(flash().attributeExists("success"));
        verify(giteaSshSetupService).setup(7L, "scan", true);
    }

    @Test
    void confirm_missingConsentDoesNotDefaultToTrue() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("confirmation required"))
                .when(giteaSshSetupService).setup(7L, "scan", false);
        mockMvc.perform(post("/git-integrations/7/ssh/setup").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("confirmation", "scan"))
                .andExpect(redirectedUrl("/git-integrations/7/edit")).andExpect(flash().attributeExists("error"));
    }

    @Test
    void save_managedKeyRotationPreservesHostTrustAfterOrderedCleanup() throws Exception {
        GitIntegration existing = managedIntegration();
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, null)).thenReturn(true);
        mockMvc.perform(post("/git-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("id", "7").param("name", "production").param("providerType", "GITEA")
                        .param("url", existing.getUrl()).param("transport", "SSH").param("sshPrivateKey", "new-private"))
                .andExpect(flash().attributeExists("success"));
        var order = org.mockito.Mockito.inOrder(gitIntegrationService, giteaSshSetupService);
        order.verify(gitIntegrationService).validateSave(any(), eq(false), eq(true));
        order.verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L);
        order.verify(giteaSshSetupService).removeManagedKey(existing, null);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L);
        order.verify(gitIntegrationService).save(argThat(input -> input.getTransport() == GitTransport.SSH
                && "new-private".equals(input.getSshPrivateKey()) && "trusted-hosts".equals(input.getSshKnownHosts())),
                eq(false), eq(true));
    }

    @Test
    void save_failedManagedCleanupDoesNotSaveNewEndpointOrClearTracking() throws Exception {
        GitIntegration existing = managedIntegration();
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(existing);
        mockMvc.perform(post("/git-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("id", "7").param("name", "production").param("providerType", "GITEA")
                        .param("url", "https://new.example.com").param("transport", "SSH").param("token", "new-token")
                        .param("sshPrivateKey", "replacement-key").param("sshKnownHosts", "new-hosts"))
                .andExpect(flash().attributeExists("error"));
        verify(giteaSshSetupService).removeManagedKey(existing, null);
        verify(gitIntegrationService, org.mockito.Mockito.never()).save(any(), anyBoolean(), anyBoolean());
        verify(gitIntegrationService, org.mockito.Mockito.never()).finishManagedSshKeyRemoval(any());
    }

    @Test
    void save_explicitHttpSelectionUsesReplacementTokenForCleanup() throws Exception {
        GitIntegration existing = managedIntegration();
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, "new-token")).thenReturn(true);
        mockMvc.perform(post("/git-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("id", "7").param("name", "production").param("providerType", "GITEA")
                        .param("url", existing.getUrl()).param("transport", "HTTP").param("token", "new-token"))
                .andExpect(flash().attributeExists("success"));
        verify(gitIntegrationService).save(argThat(input -> input.getTransport() == GitTransport.HTTP
                && "new-token".equals(input.getToken()) && input.getSshPrivateKey() == null), eq(false), eq(true));
    }

    @Test
    void delete_failureRetainsIntegrationAndRetryFinishesCleanupBeforeDeletion() throws Exception {
        GitIntegration existing = managedIntegration();
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, null)).thenReturn(false, true);
        mockMvc.perform(post("/git-integrations/7/delete").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        verify(gitIntegrationService, org.mockito.Mockito.never()).deleteById(7L);
        verify(gitIntegrationService, org.mockito.Mockito.never()).finishManagedSshKeyRemoval(7L);
        mockMvc.perform(post("/git-integrations/7/delete").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("success"));
        var order = org.mockito.Mockito.inOrder(gitIntegrationService);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L);
        order.verify(gitIntegrationService).deleteById(7L);
    }

    @Test
    void delete_assignedIntegrationDoesNotStartRemoteCleanup() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("used by bot"))
                .when(gitIntegrationService).validateDelete(7L);
        mockMvc.perform(post("/git-integrations/7/delete").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(flash().attributeExists("error"));
        org.mockito.Mockito.verifyNoInteractions(giteaSshSetupService);
        verify(gitIntegrationService, org.mockito.Mockito.never()).prepareManagedSshKeyRemoval(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "confirm", "save", "delete"})
    void upstreamFailureDoesNotExposeResponseBodyOrCause(String operation, CapturedOutput output) throws Exception {
        String sentinel = "test-only-response-token-sentinel";
        var upstream = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Bad request", null,
                sentinel.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        var failure = new IllegalStateException("upstream failed: " + sentinel, upstream);
        var request = post("/git-integrations/save");
        switch (operation) {
            case "preview" -> {
                when(giteaSshSetupService.preview(7L)).thenThrow(failure);
                request = get("/git-integrations/7/ssh/setup");
            }
            case "confirm" -> {
                when(giteaSshSetupService.setup(7L, "scan", true)).thenThrow(failure);
                request = post("/git-integrations/7/ssh/setup").param("confirmation", "scan").param("confirmed", "true");
            }
            case "save" -> when(gitIntegrationService.save(any(), anyBoolean(), anyBoolean())).thenThrow(failure);
            case "delete" -> {
                org.mockito.Mockito.doThrow(failure).when(gitIntegrationService).validateDelete(7L);
                request = post("/git-integrations/7/delete");
            }
        }
        var result = mockMvc.perform(request.with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error")).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(result.getFlashMap().toString().contains(sentinel));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains(sentinel));
    }

    private GitIntegration managedIntegration() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setName("production");
        integration.setUrl("https://gitea.example.com");
        integration.setToken("stored-token");
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        integration.setSshKnownHosts("trusted-hosts");
        integration.setSshRemoteKeyId(42L);
        integration.setSshRemoteKeyOwnerId(17L);
        integration.setSshRemoteKeyTitle("unique-title");
        return integration;
    }
}
