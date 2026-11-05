package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The layout renders exactly one of the four banner messages from this attribute, so the
 * attribute must be the derived state and nothing else.
 */
class ConfigBannerAdviceTest {

    @Test
    void exposesTheDerivedStateName() {
        ConfigMode mode = new ConfigMode("jdbc:h2:mem:test");
        mode.markDocumentPresent();
        mode.markApplied(6);

        assertEquals("APPLIED_EPHEMERAL", new ConfigBannerAdvice(mode).configState());
    }

    @Test
    void unconfiguredInstanceReportsNothing() {
        assertEquals("NOT_CONFIGURED",
                new ConfigBannerAdvice(new ConfigMode("jdbc:postgresql://db/giteabot")).configState());
    }
}
