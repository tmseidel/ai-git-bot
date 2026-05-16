package org.remus.giteabot.agent.loop;

/**
 * How an {@link AgentStrategy} wants the {@link AgentLoop} to talk to the
 * underlying {@link org.remus.giteabot.ai.AiClient}.
 *
 * <p>Step 6: a strategy may opt in to native function/tool calling by
 * returning {@link #NATIVE} from {@link AgentStrategy#preferredToolMode()}
 * and supplying non-empty {@link AgentStrategy#toolDescriptors()}. The loop
 * still falls back to {@link #LEGACY} (text {@code chat}) when the
 * configured client reports
 * {@link org.remus.giteabot.ai.AiClient#supportsNativeTools() supportsNativeTools()
 * == false} — typically because the operator flipped the per-integration
 * {@code use_legacy_tool_calling} switch.</p>
 */
public enum ToolingMode {
    LEGACY,
    NATIVE;

    /**
     * Resolve the effective tooling mode given the strategy's preferences and
     * the configured client. Returns {@link #NATIVE} only when all of the
     * following hold:
     * <ul>
     *   <li>{@code preferredMode} is {@link #NATIVE},</li>
     *   <li>{@code clientSupportsNativeTools} is {@code true} (i.e. the operator
     *       has not flipped the per-integration {@code use_legacy_tool_calling}
     *       switch and the provider implements native function calling), and</li>
     *   <li>{@code hasToolDescriptors} is {@code true} (the strategy actually
     *       advertises at least one descriptor for the API).</li>
     * </ul>
     * Otherwise {@link #LEGACY} is returned. This helper is shared between the
     * {@link AgentLoop} (deciding which transport to call) and the system-prompt
     * assembly (deciding whether to keep the JSON-protocol guidance).
     */
    public static ToolingMode resolve(ToolingMode preferredMode,
                                      boolean clientSupportsNativeTools,
                                      boolean hasToolDescriptors) {
        if (preferredMode != NATIVE) {
            return LEGACY;
        }
        if (!clientSupportsNativeTools) {
            return LEGACY;
        }
        if (!hasToolDescriptors) {
            return LEGACY;
        }
        return NATIVE;
    }
}
