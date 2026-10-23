package org.remus.giteabot.admin;

import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.ai.ModelFlavor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.openrouter.OpenRouterRegion;
import org.remus.giteabot.ai.openrouter.OpenRouterDataCollection;
import org.remus.giteabot.ai.openrouter.OpenRouterProviderMetadata;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.GOOGLE));
        when(providerRegistry.getDefaultApiUrls()).thenReturn(Map.of(AiProviderType.GOOGLE, "https://generativelanguage.googleapis.com"));
        when(providerRegistry.getSuggestedModels()).thenReturn(Map.of(AiProviderType.GOOGLE, List.of("gemini-2.5-flash")));
        when(providerRegistry.getApiKeyRequirements()).thenReturn(Map.of(AiProviderType.GOOGLE, true));
        when(providerRegistry.getFlavors()).thenReturn(Map.of(AiProviderType.GOOGLE, List.of()));

        mockMvc.perform(get("/ai-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("ai-integrations/form"))
                .andExpect(content().string(containsString("value=\"GOOGLE\"")))
                .andExpect(content().string(containsString(">Google Gemini</option>")))
                .andExpect(content().string(containsString("https:\\/\\/generativelanguage.googleapis.com")))
                .andExpect(content().string(containsString("gemini-2.5-flash")))
                .andExpect(content().string(containsString("Google AI uses the Gemini REST API")))
                .andExpect(content().string(containsString("API key required")));
    }

    @Test
    void editForm_showsOpenRouterSettingsWithoutEchoingTheKey() throws Exception {
        AiIntegration integration = new AiIntegration();
        integration.setId(7L);
        integration.setProviderType(AiProviderType.OPENROUTER);
        integration.setApiUrl("https://eu.openrouter.ai/api");
        integration.setApiKey("private-stored-ciphertext");
        integration.setOpenRouterRegion(OpenRouterRegion.EU);
        when(aiIntegrationService.findById(7L)).thenReturn(Optional.of(integration));
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.OPENROUTER));
        when(providerRegistry.getSuggestedModels()).thenReturn(
                Map.of(AiProviderType.OPENROUTER, OpenRouterProviderMetadata.SUGGESTED_MODELS));

        mockMvc.perform(get("/ai-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">OpenRouter</option>")))
                .andExpect(content().string(containsString("openrouter\\/auto")))
                .andExpect(content().string(containsString("deepseek\\/deepseek-v4.1-flash")))
                .andExpect(content().string(containsString("z-ai\\/glm-5.3-flash")))
                .andExpect(content().string(containsString("xiaomi\\/mimo-v2.6-flash")))
                .andExpect(content().string(containsString("id=\"apiUrlText\">https://eu.openrouter.ai/api<")))
                .andExpect(content().string(containsString("id=\"openRouterRegion\"")))
                .andExpect(content().string(containsString("id=\"openRouterDataCollection\"")))
                .andExpect(content().string(containsString("id=\"openRouterZdr\"")))
                .andExpect(content().string(containsString("Enterprise in-region routing")))
                .andExpect(content().string(not(containsString("private-stored-ciphertext"))));
    }

    @Test
    void save_bindsOpenRouterPrivacySettings() throws Exception {
        mockMvc.perform(post("/ai-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("providerType", "OPENROUTER").param("name", "OpenRouter")
                        .param("model", "author/model").param("apiKey", "new-key")
                        .param("openRouterRegion", "US").param("openRouterDataCollection", "ALLOW")
                        .param("openRouterZdr", "true"))
                .andExpect(redirectedUrl("/ai-integrations"));

        verify(aiIntegrationService).save(argThat(integration -> integration.getOpenRouterRegion() == OpenRouterRegion.US
                && integration.getOpenRouterDataCollection() == OpenRouterDataCollection.ALLOW
                && integration.isOpenRouterZdr()), eq(false));
    }

    @Test
    void save_rejectsUnknownOpenRouterRegionBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/ai-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("providerType", "OPENROUTER").param("apiKey", "private-new-key")
                        .param("openRouterRegion", "https://untrusted.example"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("private-new-key"))));

        verifyNoInteractions(aiIntegrationService);
    }

    @ParameterizedTest
    @CsvSource({
            "maxTokens, ''", "maxTokens, private-invalid-value", "maxTokens, 2147483648",
            "contextWindowTokens, ''", "contextWindowTokens, private-invalid-value", "contextWindowTokens, 2147483648"
    })
    void save_invalidTokenNumbersShowASafeFlashMessage(String field, String value) throws Exception {
        mockMvc.perform(post("/ai-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .locale(Locale.ENGLISH)
                        .param("providerType", "OPENROUTER").param("apiKey", "private-new-key")
                        .param(field, value))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ai-integrations"))
                .andExpect(flash().attribute("error",
                        "Please enter valid numbers for the token limits and parallel worker limit."));

        verifyNoInteractions(aiIntegrationService);
    }

    @ParameterizedTest
    @CsvSource({
            "openRouterRegion, private-invalid-region",
            "openRouterDataCollection, private-invalid-policy",
            "openRouterZdr, private-invalid-boolean"
    })
    void save_malformedPrivacySettingsStayBadRequestsEvenWithNumericErrors(String field, String value) throws Exception {
        mockMvc.perform(post("/ai-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("providerType", "OPENROUTER").param("apiKey", "private-new-key")
                        .param("maxTokens", "").param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("private-new-key"))))
                .andExpect(content().string(not(containsString(value))));

        verifyNoInteractions(aiIntegrationService);
    }

    @Test
    void save_mixedNumericErrorsUseTheGenericFlashMessage() throws Exception {
        mockMvc.perform(post("/ai-integrations/save").with(user("admin").roles("ADMIN")).with(csrf())
                        .locale(Locale.ENGLISH)
                        .param("providerType", "OLLAMA").param("maxTokens", "")
                        .param("parallelWorkerLimit", "21"))
                .andExpect(redirectedUrl("/ai-integrations"))
                .andExpect(flash().attribute("error",
                        "Please enter valid numbers for the token limits and parallel worker limit."));

        verifyNoInteractions(aiIntegrationService);
    }

    @Test
    void save_googleAiIntegrationDelegatesToService() throws Exception {
        mockMvc.perform(post("/ai-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "Google AI")
                        .param("providerType", "GOOGLE")
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
                                && integration.getProviderType() == AiProviderType.GOOGLE
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
                        .param("providerType", "GOOGLE")
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
        existing.setProviderType(AiProviderType.ANTHROPIC);
        existing.setApiUrl("https://api.anthropic.com");
        existing.setModel("claude-sonnet-4");
        when(aiIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.ANTHROPIC));
        when(providerRegistry.getDefaultApiUrls()).thenReturn(Map.of(AiProviderType.ANTHROPIC, "https://api.anthropic.com"));
        when(providerRegistry.getSuggestedModels()).thenReturn(Map.of(AiProviderType.ANTHROPIC, List.of("claude-sonnet-4")));
        when(providerRegistry.getApiKeyRequirements()).thenReturn(Map.of(AiProviderType.ANTHROPIC, true));
        when(providerRegistry.getFlavors()).thenReturn(Map.of(AiProviderType.ANTHROPIC, List.of()));

        mockMvc.perform(get("/ai-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("ai-integrations/form"))
                .andExpect(content().string(containsString("id=\"clearApiKeyBtn\"")))
                .andExpect(content().string(containsString("id=\"apiKeyClearPendingHint\"")));
    }

    @Test
    void newForm_hidesModelFlavorUntilAProviderWithFlavorsIsSelected() throws Exception {
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.OPENAI));
        when(providerRegistry.getFlavors()).thenReturn(Map.of(AiProviderType.OPENAI,
                List.of(new ModelFlavor(AiProviderType.OPENAI, "standard", "Standard", ""))));

        mockMvc.perform(get("/ai-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"modelFlavorField\" hidden=\"hidden\"")));
    }

    @Test
    void editForm_showsModelFlavorForProviderWithFlavors() throws Exception {
        AiIntegration existing = new AiIntegration();
        existing.setId(7L);
        existing.setName("Existing");
        existing.setProviderType(AiProviderType.OPENAI);
        existing.setApiUrl("https://api.openai.com");
        existing.setModel("gpt-6.1-sol");
        when(aiIntegrationService.findById(7L)).thenReturn(Optional.of(existing));
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.OPENAI));
        when(providerRegistry.getFlavors()).thenReturn(Map.of(AiProviderType.OPENAI,
                List.of(new ModelFlavor(AiProviderType.OPENAI, "standard", "Standard", ""))));

        mockMvc.perform(get("/ai-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"modelFlavorField\"")))
                .andExpect(content().string(not(containsString("id=\"modelFlavorField\" hidden"))));
    }

    @Test
    void newForm_showsParallelWorkerLimitInputAndHelp() throws Exception {
        when(providerRegistry.getProviderTypes()).thenReturn(List.of(AiProviderType.ANTHROPIC));
        when(providerRegistry.getDefaultApiUrls()).thenReturn(Map.of(AiProviderType.ANTHROPIC, "https://api.anthropic.com"));
        when(providerRegistry.getSuggestedModels()).thenReturn(Map.of(AiProviderType.ANTHROPIC, List.of("claude-sonnet-4")));
        when(providerRegistry.getApiKeyRequirements()).thenReturn(Map.of(AiProviderType.ANTHROPIC, true));
        when(providerRegistry.getFlavors()).thenReturn(Map.of(AiProviderType.ANTHROPIC, List.of()));

        mockMvc.perform(get("/ai-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"parallelWorkerLimit\"")))
                .andExpect(content().string(containsString("name=\"parallelWorkerLimit\"")))
                .andExpect(content().string(not(containsString(">20</option>"))))
                .andExpect(content().string(containsString("min=\"0\"")))
                .andExpect(content().string(containsString("max=\"20\"")))
                .andExpect(content().string(containsString("Parallel worker limit")))
                .andExpect(content().string(containsString(
                        "0 = unlimited. 1–20 caps concurrent jobs")));
    }

    @Test
    void save_rejectsAParallelWorkerLimitOutsideTheRange() throws Exception {
        for (String value : List.of("-5", "21", "", "private-invalid-value", "2147483648")) {
            mockMvc.perform(post("/ai-integrations/save")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .locale(Locale.ENGLISH)
                            .param("name", "Anthropic")
                            .param("providerType", "ANTHROPIC")
                            .param("apiUrl", "https://api.anthropic.com")
                            .param("model", "claude-sonnet-4")
                            .param("parallelWorkerLimit", value))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/ai-integrations"))
                    .andExpect(flash().attribute("error", "The parallel worker limit must be between 0 and 20."));
        }
        verify(aiIntegrationService, never()).save(any(AiIntegration.class), anyBoolean());
    }
}
