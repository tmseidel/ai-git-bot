package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiProviderRegistry;
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
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
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

@WebMvcTest(AiIntegrationController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class AiIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiIntegrationService aiIntegrationService;

    @MockitoBean
    private AiProviderRegistry providerRegistry;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void newForm_showsGoogleAiProviderAndSetupHints() throws Exception {
        when(providerRegistry.getProviderTypes()).thenReturn(List.of("google"));
        when(providerRegistry.getDisplayNames()).thenReturn(Map.of("google", "gemini"));
        when(providerRegistry.getDefaultApiUrls()).thenReturn(Map.of("google", "https://generativelanguage.googleapis.com"));
        when(providerRegistry.getSuggestedModels()).thenReturn(Map.of("google", List.of("gemini-2.5-flash")));
        when(providerRegistry.getApiKeyRequirements()).thenReturn(Map.of("google", true));
        when(providerRegistry.getFlavors()).thenReturn(Map.of("google", List.of()));

        mockMvc.perform(get("/ai-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("ai-integrations/form"))
                .andExpect(content().string(containsString("value=\"google\"")))
                .andExpect(content().string(containsString("gemini")))
                .andExpect(content().string(containsString("https:\\/\\/generativelanguage.googleapis.com")))
                .andExpect(content().string(containsString("gemini-2.5-flash")))
                .andExpect(content().string(containsString("Google AI uses the Gemini REST API")))
                .andExpect(content().string(containsString("API key required")));
    }

    @Test
    void save_googleAiIntegrationDelegatesToService() throws Exception {
        mockMvc.perform(post("/ai-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "Google AI")
                        .param("providerType", "google")
                        .param("apiUrl", "https://generativelanguage.googleapis.com")
                        .param("apiKey", "gemini-key")
                        .param("model", "gemini-2.5-flash")
                        .param("maxTokens", "4096")
                        .param("maxDiffCharsPerChunk", "120000")
                        .param("maxDiffChunks", "8")
                        .param("retryTruncatedChunkChars", "60000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ai-integrations"));

        verify(aiIntegrationService).save(argThat(integration ->
                        "Google AI".equals(integration.getName())
                                && "google".equals(integration.getProviderType())
                                && "https://generativelanguage.googleapis.com".equals(integration.getApiUrl())
                                && "gemini-key".equals(integration.getApiKey())
                                && "gemini-2.5-flash".equals(integration.getModel())
                ),
                eq(false));
    }

    @Test
    void save_blankApiKey_keepsKeyOutOfEntityAndForwardsClearFlag() throws Exception {
        mockMvc.perform(post("/ai-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "Google AI")
                        .param("providerType", "google")
                        .param("apiUrl", "https://generativelanguage.googleapis.com")
                        .param("apiKey", "")
                        .param("clearApiKey", "true")
                        .param("model", "gemini-2.5-flash"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ai-integrations"));

        // The kept ciphertext must never travel through the controller into
        // save(), where it would be encrypted a second time and corrupt the key.
        verify(aiIntegrationService).save(argThat(integration ->
                        "".equals(integration.getApiKey()) || integration.getApiKey() == null),
                eq(true));
    }

    @Test
    void editForm_showsClearPendingHint() throws Exception {
        AiIntegration existing = new AiIntegration();
        existing.setId(7L);
        existing.setName("Existing");
        existing.setProviderType("anthropic");
        existing.setApiUrl("https://api.anthropic.com");
        existing.setModel("claude-sonnet-4");
        when(aiIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(providerRegistry.getProviderTypes()).thenReturn(List.of("anthropic"));
        when(providerRegistry.getDisplayNames()).thenReturn(Map.of("anthropic", "Anthropic"));
        when(providerRegistry.getDefaultApiUrls()).thenReturn(Map.of("anthropic", "https://api.anthropic.com"));
        when(providerRegistry.getSuggestedModels()).thenReturn(Map.of("anthropic", List.of("claude-sonnet-4")));
        when(providerRegistry.getApiKeyRequirements()).thenReturn(Map.of("anthropic", true));
        when(providerRegistry.getFlavors()).thenReturn(Map.of("anthropic", List.of()));

        mockMvc.perform(get("/ai-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("ai-integrations/form"))
                .andExpect(content().string(containsString("id=\"clearApiKeyBtn\"")))
                .andExpect(content().string(containsString("id=\"apiKeyClearPendingHint\"")));
    }
}
