package org.remus.giteabot.admin;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.SshEndpoint;
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

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        existing.setLockVersion(4L);
        existing.setName("Existing");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setToken("encrypted-token");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/form"))
                .andExpect(content().string(containsString("id=\"clearTokenBtn\"")))
                .andExpect(content().string(containsString("name=\"lockVersion\" value=\"4\"")))
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
    void editForm_offersAutomaticSshSetupWhenEncryptionIsEnabled() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("Existing");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setToken("encrypted-token");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"autoSshSetupBtn\"")))
                .andExpect(content().string(containsString("href=\"/git-integrations/7/ssh/setup\"")));
    }

    @Test
    void editForm_disablesAutomaticSshSetupWithoutStoredToken() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"autoSshSetupBtn\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                         containsString("href=\"/git-integrations/7/ssh/setup\""))));
    }

    @Test
    void editForm_disablesAutomaticSshSetupWhenSshIsAlreadyConfigured() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        existing.setToken("encrypted-token");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.isEncryptionEnabled()).thenReturn(true);

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"autoSshSetupBtn\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("href=\"/git-integrations/7/ssh/setup\""))))
                .andExpect(content().string(containsString(
                        "Switch to HTTP and save before replacing the configured SSH key.")));
    }

    @Test
    void sshSetupPreview_rendersScannedFingerprintWithoutChangingConfiguration() throws Exception {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setLockVersion(4L);
        integration.setName("Production");
        var hostKeys = new SshCommandService.HostKeyScan(
                new SshEndpoint("gitea.example.com", 2222),
                "[gitea.example.com]:2222 ssh-ed25519 AQID\n",
                List.of(new SshCommandService.HostKeyFingerprint("ssh-ed25519", "SHA256:fingerprint")),
                "scan-confirmation");
        when(giteaSshSetupService.preview(7L)).thenReturn(new GiteaSshSetupService.SshSetupPreview(
                integration, "ssh://git@gitea.example.com:2222/owner/repo.git", hostKeys));

        mockMvc.perform(get("/git-integrations/7/ssh/setup").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/ssh-setup"))
                .andExpect(content().string(containsString("gitea.example.com:2222")))
                .andExpect(content().string(containsString("SHA256:fingerprint")))
                .andExpect(content().string(containsString("value=\"scan-confirmation\"")))
                .andExpect(content().string(containsString("name=\"lockVersion\" value=\"4\"")));
    }

    @Test
    void confirmSshSetup_registersKeyAndRedirectsToEditForm() throws Exception {
        mockMvc.perform(post("/git-integrations/7/ssh/setup")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("lockVersion", "4")
                        .param("confirmation", "scan-confirmation")
                        .param("confirmed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations/7/edit"));

        verify(giteaSshSetupService).setup(7L, 4L, "scan-confirmation", true);
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
    void save_clearingTokenDisablesSshAndClearsItsCredentials() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("clearToken", "true")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        verify(gitIntegrationService).save(argThat(integration ->
                        integration.getTransport() == GitTransport.HTTP
                                && integration.getSshPrivateKey() == null
                                && integration.getSshKnownHosts() == null),
                eq(true), eq(true));
    }

    @Test
    void save_sshIntegrationForwardsOneWayCredentials() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "My SSH Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("token", "gitea-token")
                        .param("transport", "SSH")
                        .param("sshPrivateKey", "plain-private-key")
                        .param("sshKnownHosts", "gitea.example.com ssh-ed25519 host-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        integration.getTransport() == GitTransport.SSH
                                && "plain-private-key".equals(integration.getSshPrivateKey())
                                && "gitea.example.com ssh-ed25519 host-key".equals(integration.getSshKnownHosts())),
                eq(false), eq(false));
    }

    @Test
    void save_replacingGeneratedCredentialsRemovesManagedGiteaKey() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setLockVersion(3L);
        existing.setName("My Gitea");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshRemoteKeyId(42L);
        GitIntegration pending = new GitIntegration();
        pending.setId(7L);
        pending.setLockVersion(4L);
        pending.setSshRemoteKeyId(42L);
        GitIntegration cleaned = new GitIntegration();
        cleaned.setId(7L);
        cleaned.setLockVersion(5L);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L, 3L)).thenReturn(pending);
        when(gitIntegrationService.finishManagedSshKeyRemoval(7L, 4L)).thenReturn(cleaned);
        when(giteaSshSetupService.removeManagedKey(pending, null)).thenReturn(true);

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("lockVersion", "3")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("sshPrivateKey", "replacement-private-key")
                        .param("sshKnownHosts", "gitea.example.com ssh-ed25519 host-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        InOrder order = inOrder(giteaSshSetupService, gitIntegrationService);
        order.verify(gitIntegrationService).validateSave(
                org.mockito.ArgumentMatchers.any(), eq(false), eq(true));
        order.verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L, 3L);
        order.verify(giteaSshSetupService).removeManagedKey(pending, null);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L, 4L);
        order.verify(gitIntegrationService).save(argThat(integration ->
                Long.valueOf(5L).equals(integration.getLockVersion())), eq(false), eq(true));
    }

    @Test
    void delete_persistsSafeStateBeforeRemovingManagedGiteaKey() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("My Gitea");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setSshRemoteKeyId(42L);
        when(gitIntegrationService.beginDelete(7L)).thenReturn(Optional.of(existing));
        when(giteaSshSetupService.removeManagedKey(existing, null)).thenReturn(true);

        mockMvc.perform(post("/git-integrations/7/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        InOrder order = inOrder(giteaSshSetupService, gitIntegrationService);
        order.verify(gitIntegrationService).beginDelete(7L);
        order.verify(giteaSshSetupService).removeManagedKey(existing, null);
        order.verify(gitIntegrationService).completeDelete(7L, null);
    }

    @Test
    void delete_retriesRemoteCleanupFromPersistedFence() throws Exception {
        GitIntegration pending = new GitIntegration();
        pending.setId(7L);
        pending.setLockVersion(5L);
        pending.setDeletionPending(true);
        pending.setSshRemoteKeyId(42L);
        when(gitIntegrationService.beginDelete(7L)).thenReturn(Optional.of(pending));
        when(giteaSshSetupService.removeManagedKey(pending, null)).thenReturn(false, true);

        mockMvc.perform(post("/git-integrations/7/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/git-integrations/7/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(gitIntegrationService, org.mockito.Mockito.times(2)).beginDelete(7L);
        verify(giteaSshSetupService, org.mockito.Mockito.times(2)).removeManagedKey(pending, null);
        verify(gitIntegrationService).completeDelete(7L, 5L);
    }

    @Test
    void save_cleanupFailureKeepsTrackedConfigurationUnchanged() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("My Gitea");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setSshRemoteKeyId(42L);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L, null)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, null)).thenReturn(false);

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("sshPrivateKey", "replacement-private-key")
                        .param("sshKnownHosts", "gitea.example.com ssh-ed25519 host-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L, null);
        verify(gitIntegrationService, never()).finishManagedSshKeyRemoval(7L, null);
        verify(gitIntegrationService, never()).save(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void save_changedGiteaUrlCleansManagedKeyUsingOldIntegration() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://old-gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshRemoteKeyId(42L);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L, null)).thenReturn(existing);
        when(gitIntegrationService.finishManagedSshKeyRemoval(7L, null)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, null)).thenReturn(true);

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://new-gitea.example.com")
                        .param("transport", "SSH")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        InOrder order = inOrder(giteaSshSetupService, gitIntegrationService);
        order.verify(gitIntegrationService).validateSave(
                org.mockito.ArgumentMatchers.any(), eq(false), eq(true));
        order.verify(gitIntegrationService).prepareManagedSshKeyRemoval(7L, null);
        order.verify(giteaSshSetupService).removeManagedKey(existing, null);
        order.verify(gitIntegrationService).finishManagedSshKeyRemoval(7L, null);
        order.verify(gitIntegrationService).save(argThat(integration ->
                integration.getTransport() == GitTransport.HTTP), eq(false), eq(true));
    }

    @Test
    void save_changedTokenUsesReplacementTokenForSameOwnerCleanup() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshKnownHosts("stored-host-key");
        existing.setSshRemoteKeyId(42L);
        existing.setSshRemoteKeyOwnerId(17L);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationService.prepareManagedSshKeyRemoval(7L, null)).thenReturn(existing);
        when(gitIntegrationService.finishManagedSshKeyRemoval(7L, null)).thenReturn(existing);
        when(giteaSshSetupService.removeManagedKey(existing, "replacement-token")).thenReturn(true);

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("token", "replacement-token")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        verify(giteaSshSetupService).removeManagedKey(existing, "replacement-token");
        verify(gitIntegrationService).finishManagedSshKeyRemoval(7L, null);
        verify(gitIntegrationService).save(argThat(integration ->
                integration.getTransport() == GitTransport.HTTP), eq(false), eq(true));
    }

    @Test
    void save_staleFormDoesNotRemoveManagedKey() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        existing.setTransport(GitTransport.SSH);
        existing.setSshPrivateKey("encrypted-private-key");
        existing.setSshRemoteKeyId(42L);
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        doThrow(new OptimisticLockException("stale form")).when(gitIntegrationService)
                .validateSave(org.mockito.ArgumentMatchers.any(), eq(false), eq(true));

        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("lockVersion", "3")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("transport", "SSH")
                        .param("sshPrivateKey", "invalid-private-key")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection());

        verify(gitIntegrationService, never()).prepareManagedSshKeyRemoval(
                eq(7L), org.mockito.ArgumentMatchers.any());
        verify(giteaSshSetupService, never()).removeManagedKey(existing, null);
        verify(gitIntegrationService, never()).save(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void delete_validationFailureDoesNotRemoveManagedKey() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setSshRemoteKeyId(42L);
        doThrow(new IllegalStateException("still in use")).when(gitIntegrationService).beginDelete(7L);

        mockMvc.perform(post("/git-integrations/7/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(giteaSshSetupService, never()).removeManagedKey(existing, null);
        verify(gitIntegrationService, never()).completeDelete(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
