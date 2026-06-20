package org.remus.giteabot.webhook;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.*;
import org.remus.giteabot.bitbucket.BitbucketWebhookHandler;
import org.remus.giteabot.gitea.GiteaWebhookHandler;
import org.remus.giteabot.github.GitHubWebhookHandler;
import org.remus.giteabot.gitlab.GitLabWebhookHandler;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UnifiedWebhookController.class)
@ActiveProfiles("test")
class UnifiedWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotService botService;

    @MockitoBean
    private GiteaWebhookHandler giteaHandler;

    @MockitoBean
    private GitHubWebhookHandler gitHubHandler;

    @MockitoBean
    private BitbucketWebhookHandler bitbucketHandler;

    @MockitoBean
    private GitLabWebhookHandler gitLabHandler;

    @Test
    void handleWebhook_giteaBot_delegatesToGiteaHandler() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITEA);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(giteaHandler.handleWebhook(eq(bot), any())).thenReturn(ResponseEntity.ok("review triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(giteaHandler).handleWebhook(eq(bot), any(Map.class));
        verify(gitHubHandler, never()).handleWebhook(any(), any(), any());
        verify(bitbucketHandler, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void handleWebhook_githubBot_delegatesToGitHubHandler() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITHUB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(gitHubHandler.handleWebhook(eq(bot), eq("pull_request"), any())).thenReturn(ResponseEntity.ok("review triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(gitHubHandler).handleWebhook(eq(bot), eq("pull_request"), any(Map.class));
        verify(giteaHandler, never()).handleWebhook(any(), any());
        verify(bitbucketHandler, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void handleWebhook_bitbucketBot_delegatesToBitbucketHandler() throws Exception {
        Bot bot = createTestBot(RepositoryType.BITBUCKET);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(bitbucketHandler.handleWebhook(eq(bot), eq("pullrequest:created"), any())).thenReturn(ResponseEntity.ok("review triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content("{\"pullrequest\":{\"id\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(bitbucketHandler).handleWebhook(eq(bot), eq("pullrequest:created"), any(Map.class));
        verify(giteaHandler, never()).handleWebhook(any(), any());
        verify(gitHubHandler, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void handleWebhook_botNotFound_returns404() throws Exception {
        when(botService.findByWebhookSecret("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/webhook/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void handleWebhook_botDisabled_returnsBotDisabled() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITEA);
        bot.setEnabled(false);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("bot disabled"));

        verify(giteaHandler, never()).handleWebhook(any(), any());
    }

    @Test
    void handleWebhook_gitlabBot_delegatesToGitLabHandler() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITLAB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(gitLabHandler.handleWebhook(eq(bot), eq("Merge Request Hook"), any())).thenReturn(ResponseEntity.ok("review triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .content("{\"object_kind\":\"merge_request\",\"object_attributes\":{\"iid\":1,\"action\":\"open\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(gitLabHandler).handleWebhook(eq(bot), eq("Merge Request Hook"), any(Map.class));
        verify(giteaHandler, never()).handleWebhook(any(), any());
        verify(gitHubHandler, never()).handleWebhook(any(), any(), any());
        verify(bitbucketHandler, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void handleWebhook_gitlabBot_issueHook_delegatesToGitLabHandler() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITLAB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(gitLabHandler.handleWebhook(eq(bot), eq("Issue Hook"), any())).thenReturn(ResponseEntity.ok("agent triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Event", "Issue Hook")
                        .content("{\"object_kind\":\"issue\",\"object_attributes\":{\"iid\":1,\"action\":\"update\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("agent triggered"));

        verify(gitLabHandler).handleWebhook(eq(bot), eq("Issue Hook"), any(Map.class));
        verify(giteaHandler, never()).handleWebhook(any(), any());
        verify(gitHubHandler, never()).handleWebhook(any(), any(), any());
        verify(bitbucketHandler, never()).handleWebhook(any(), any(), any());
    }

    private Bot createTestBot(RepositoryType type) {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("Test Bot");
        bot.setUsername("ai_bot");
        bot.setWebhookSecret("test-secret");
        bot.setEnabled(true);

        AiIntegration ai = new AiIntegration();
        ai.setId(1L);
        ai.setName("Test AI");
        ai.setProviderType("anthropic");
        ai.setApiUrl("http://localhost:8081");
        ai.setModel("claude-sonnet-4-20250514");
        ai.setMaxTokens(4096);
        ai.setCreatedAt(Instant.now());
        ai.setUpdatedAt(Instant.now());
        bot.setAiIntegration(ai);

        GitIntegration git = new GitIntegration();
        git.setId(1L);
        git.setName("Test Git");
        git.setProviderType(type);
        git.setUrl("http://localhost");
        git.setToken("test_token");
        git.setCreatedAt(Instant.now());
        git.setUpdatedAt(Instant.now());
        bot.setGitIntegration(git);

        return bot;
    }
}

