package org.remus.giteabot.ai.openrouter;

import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.ModelFlavor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** First-class OpenRouter configuration and credential validation. */
@Component
public class OpenRouterProviderMetadata implements AiProviderMetadata {
    private final RestClient.Builder restClientBuilder;

    /** Retains configured timeouts/TLS settings, but never redirects a credential to another host. */
    public OpenRouterProviderMetadata(ObjectProvider<RestClient.Builder> builders, HttpClientSettings settings) {
        this.restClientBuilder = builders.getObject().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(settings.withRedirects(HttpRedirects.DONT_FOLLOW)));
    }

    @Override public String getProviderType() { return "openrouter"; }
    @Override public String getDisplayName() { return "OpenRouter"; }
    @Override public String getDefaultApiUrl() { return OpenRouterRegion.GLOBAL.getApiRoot(); }
    @Override public List<String> getSuggestedModels() { return List.of(); }
    @Override public boolean requiresApiKey() { return true; }

    @Override
    public List<ModelFlavor> getFlavors() {
        return List.of(new ModelFlavor("openrouter", "standard", "Provider default",
                "Use the selected model's default reasoning behavior."));
    }

    @Override
    public void validateConfiguration(AiIntegration integration, String apiKey) {
        validateSettings(integration);
        integration.setApiUrl(integration.getOpenRouterRegion().getApiRoot());
        if (apiKey == null || apiKey.isBlank()) {
            if (integration.getId() == null) {
                throw new IllegalArgumentException("OpenRouter requires an inference API key");
            }
            return;
        }
        JsonNode response;
        try {
            response = buildRestClient(integration, apiKey).get().uri("/v1/key").retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Provider error bodies may contain credential/account details. Do not expose them in flash messages or logs.
            throw new IllegalArgumentException("OpenRouter key verification failed (HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            throw new IllegalArgumentException("OpenRouter key verification is unavailable");
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.path("is_management_key").isBoolean() || data.path("is_management_key").asBoolean()
                || !data.path("is_provisioning_key").isBoolean() || data.path("is_provisioning_key").asBoolean()) {
            throw new IllegalArgumentException("OpenRouter key is not a verified inference key");
        }
        boolean allowed = false;
        if (data.path("allowed_data_regions").isArray()) {
            for (JsonNode region : data.path("allowed_data_regions")) {
                allowed |= region.isString() && region.asString().equals(integration.getOpenRouterRegion().getDataRegion());
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException("The OpenRouter key/account does not allow the selected region");
        }
    }

    @Override
    public RestClient buildRestClient(AiIntegration integration, String decryptedApiKey) {
        validateSettings(integration);
        if (decryptedApiKey == null || decryptedApiKey.isBlank()) {
            throw new IllegalArgumentException("OpenRouter requires an inference API key");
        }
        return restClientBuilder.clone()
                .baseUrl(integration.getOpenRouterRegion().getApiRoot())
                .defaultHeader("Authorization", "Bearer " + decryptedApiKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultStatusHandler(HttpStatusCode::is3xxRedirection, (request, response) -> {
                    throw new RestClientException("OpenRouter redirects are not allowed");
                })
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        validateSettings(integration);
        return new OpenRouterClient(restClient, integration.getModel(), integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling(), new OpenRouterRequest.ProviderPreferences(true, false,
                integration.getOpenRouterDataCollection().getWireValue(), integration.isOpenRouterZdr()));
    }

    private void validateSettings(AiIntegration integration) {
        if (integration.getOpenRouterRegion() == null || integration.getOpenRouterDataCollection() == null) {
            throw new IllegalArgumentException("Select a valid OpenRouter region and data-collection policy");
        }
        if (integration.getModel() == null || integration.getModel().isBlank()
                || integration.getMaxTokens() <= 0 || integration.getContextWindowTokens() <= 0) {
            throw new IllegalArgumentException("OpenRouter requires a model ID and positive token limits");
        }
        String flavor = integration.getModelFlavor();
        if (flavor != null && !flavor.isBlank() && !"standard".equals(flavor)) {
            throw new IllegalArgumentException("OpenRouter currently supports the provider-default model flavor");
        }
    }
}
