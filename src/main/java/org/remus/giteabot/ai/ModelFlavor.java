package org.remus.giteabot.ai;

import org.remus.giteabot.admin.AiIntegration;
import org.springframework.web.client.RestClient;

/**
 * Metadata for a provider-specific, per-model request preset ("flavor").
 *
 * <p>A flavor is a named configuration an operator can pick per
 * {@link org.remus.giteabot.admin.AiIntegration} (e.g. "no reasoning effort"
 * for an OpenAI reasoning model whose gateway defaults
 * {@code reasoning_effort} on). The record carries display metadata only —
 * the actual request behavior is provider-internal and resolved by each
 * provider's {@link AiProviderMetadata#createClient(RestClient, AiIntegration)}
 * from the persisted flavor id.
 *
 * <p>{@code providerType} (the {@code ai_integrations.provider_type} value,
 * e.g. {@code "openai"}) namespaces the {@code id}. A bare id like
 * {@code "standard"} is only meaningful within one provider and would collide
 * across providers; pairing it with the provider type makes every flavor
 * globally unique, so a flat collection of flavors across providers can never
 * conflate two entries.
 *
 * <p>The conventional id {@code "standard"} means "provider default
 * behaviour"; every provider that exposes flavors must include it so the
 * persisted column always has a valid value.
 */
public record ModelFlavor(String providerType, String id, String label, String description) {
}
