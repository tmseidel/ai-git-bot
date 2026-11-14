package org.remus.giteabot.webhook;

import org.remus.giteabot.ai.AiProviderType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.*;
import org.remus.giteabot.azuredevops.AzureDevopsWebhookTranslator;
import org.remus.giteabot.bitbucket.BitbucketWebhookTranslator;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.gitea.GiteaWebhookTranslator;
import org.remus.giteabot.github.GitHubWebhookTranslator;
import org.remus.giteabot.gitlab.GitLabWebhookTranslator;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private GiteaWebhookTranslator giteaTranslator;

    @MockitoBean
    private WebhookEventDispatcher dispatcher;

    @MockitoBean
    private GitHubWebhookTranslator gitHubTranslator;

    @MockitoBean
    private BitbucketWebhookTranslator bitbucketTranslator;

    @MockitoBean
    private GitLabWebhookTranslator gitLabTranslator;

    @MockitoBean
    private AzureDevopsWebhookTranslator azureDevopsTranslator;

    @Test
    void handleWebhook_giteaBot_translatesAndDispatches() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITEA);
        WebhookEvent event = WebhookEvent.builder()
                .kind(WebhookEventKind.PR_OPENED).payload(new WebhookPayload()).build();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(giteaTranslator.providerType()).thenReturn(RepositoryType.GITEA);
        when(giteaTranslator.translate(eq(bot), any(), any())).thenReturn(event);
        when(dispatcher.dispatch(bot, event)).thenReturn(ResponseEntity.ok("review triggered"));

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitea-Event", "pull_request")
                        .content("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(giteaTranslator).translate(eq(bot), headers.capture(), any(Map.class));
        // Header names reach translators case-insensitively, whatever casing the client sent.
        assertEquals("pull_request", headers.getValue().get("x-gitea-event"));
        assertEquals("pull_request", headers.getValue().get("X-GITEA-EVENT"));
        verify(dispatcher).dispatch(bot, event);
        verify(gitHubTranslator, never()).translate(any(), any(), any());
        verify(bitbucketTranslator, never()).translate(any(), any(), any());
    }

    @Test
    void handleWebhook_githubBot_translatesAndDispatches() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITHUB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        stubTranslation(gitHubTranslator, RepositoryType.GITHUB, bot, "review triggered");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(gitHubTranslator).translate(eq(bot), any(), any(Map.class));
        verify(giteaTranslator, never()).translate(any(), any(), any());
        verify(bitbucketTranslator, never()).translate(any(), any(), any());
    }

    @Test
    void handleWebhook_githubBot_acceptsValidSignature() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITHUB);
        String payload = "{\"action\":\"opened\",\"pull_request\":{\"number\":1}}";
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botService.getDecryptedWebhookSigningSecret(bot)).thenReturn("webhook-secret");
        stubTranslation(gitHubTranslator, RepositoryType.GITHUB, bot, "review triggered");
        when(gitHubTranslator.verifySignature(eq("webhook-secret"), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .header("x-hub-signature-256", "sha256=signature")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        // The translator checks the exact body bytes, with header names matched case-insensitively.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(gitHubTranslator).verifySignature(eq("webhook-secret"), headers.capture(), body.capture());
        assertEquals("sha256=signature", headers.getValue().get("X-Hub-Signature-256"));
        assertEquals(payload, new String(body.getValue(), StandardCharsets.UTF_8));
        verify(gitHubTranslator).translate(eq(bot), any(), any(Map.class));
    }

    @Test
    void handleWebhook_githubBot_rejectsInvalidSignatureBeforeDispatch() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITHUB);
        bot.setWebhookSigningSecret("encrypted-signing-secret");
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botService.getDecryptedWebhookSigningSecret(bot)).thenReturn("webhook-secret");
        when(gitHubTranslator.providerType()).thenReturn(RepositoryType.GITHUB);
        when(gitHubTranslator.verifySignature(any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=invalid")
                        .content("{\"action\":\"opened\",\"pull_request\":{\"number\":1}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("invalid signature"));

        verify(gitHubTranslator, never()).translate(any(), any(), any());
        verify(botService, never()).incrementWebhookCallCount(bot);
    }

    @Test
    void handleWebhook_withoutSigningSecret_skipsSignatureVerification() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITHUB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botService.getDecryptedWebhookSigningSecret(bot)).thenReturn(" ");
        stubTranslation(gitHubTranslator, RepositoryType.GITHUB, bot, "review triggered");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .content("{\"action\":\"opened\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(gitHubTranslator, never()).verifySignature(any(), any(), any());
    }

    @Test
    void handleWebhook_bitbucketBot_translatesAndDispatches() throws Exception {
        Bot bot = createTestBot(RepositoryType.BITBUCKET);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        stubTranslation(bitbucketTranslator, RepositoryType.BITBUCKET, bot, "review triggered");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Key", "pullrequest:created")
                        .content("{\"pullrequest\":{\"id\":1}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(bitbucketTranslator).translate(eq(bot), any(), any(Map.class));
        verify(giteaTranslator, never()).translate(any(), any(), any());
        verify(gitHubTranslator, never()).translate(any(), any(), any());
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

        verify(giteaTranslator, never()).translate(any(), any(), any());
    }

    @Test
    void handleWebhook_gitlabBot_translatesAndDispatches() throws Exception {
        Bot bot = createTestBot(RepositoryType.GITLAB);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        stubTranslation(gitLabTranslator, RepositoryType.GITLAB, bot, "review triggered");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .content("{\"object_kind\":\"merge_request\",\"object_attributes\":{\"iid\":1,\"action\":\"open\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(gitLabTranslator).translate(eq(bot), any(), any(Map.class));
        verify(giteaTranslator, never()).translate(any(), any(), any());
        verify(gitHubTranslator, never()).translate(any(), any(), any());
        verify(bitbucketTranslator, never()).translate(any(), any(), any());
    }

    private void stubTranslation(WebhookTranslator translator, RepositoryType type, Bot bot, String response) {
        WebhookEvent event = WebhookEvent.builder()
                .kind(WebhookEventKind.PR_OPENED).payload(new WebhookPayload()).build();
        when(translator.providerType()).thenReturn(type);
        when(translator.translate(eq(bot), any(), any())).thenReturn(event);
        when(dispatcher.dispatch(bot, event)).thenReturn(ResponseEntity.ok(response));
    }

    private Bot createTestBot(RepositoryType type) {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("Test Bot");
        bot.setWebhookSecret("test-secret");
        bot.setEnabled(true);

        AiIntegration ai = new AiIntegration();
        ai.setId(1L);
        ai.setName("Test AI");
        ai.setProviderType(AiProviderType.ANTHROPIC);
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
        git.setUsername("ai_bot");
        git.setCreatedAt(Instant.now());
        git.setUpdatedAt(Instant.now());
        bot.setGitIntegration(git);

        return bot;
    }
}
