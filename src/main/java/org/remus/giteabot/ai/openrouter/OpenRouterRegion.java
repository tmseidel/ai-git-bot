package org.remus.giteabot.ai.openrouter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Official OpenRouter hosts; the regional routes require an eligible account. */
@Getter
@RequiredArgsConstructor
public enum OpenRouterRegion {
    GLOBAL("https://openrouter.ai/api", "global"),
    EU("https://eu.openrouter.ai/api", "europe"),
    US("https://us.openrouter.ai/api", "us");

    private final String apiRoot;
    private final String dataRegion;
}
