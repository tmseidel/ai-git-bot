package org.remus.giteabot.ai.openrouter;

import java.util.Locale;

/** Operator-selected data-collection policy for OpenRouter routing. */
public enum OpenRouterDataCollection {
    DENY, ALLOW;

    /** Returns the lower-case value used by the OpenRouter API. */
    public String getWireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
