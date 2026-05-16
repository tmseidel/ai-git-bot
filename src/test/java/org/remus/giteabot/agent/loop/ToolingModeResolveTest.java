package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolingModeResolveTest {

    @Test
    void preferredLegacyAlwaysReturnsLegacy() {
        assertEquals(ToolingMode.LEGACY, ToolingMode.resolve(ToolingMode.LEGACY, true, true));
        assertEquals(ToolingMode.LEGACY, ToolingMode.resolve(ToolingMode.LEGACY, false, false));
    }

    @Test
    void preferredNativeFallsBackToLegacyWhenClientDoesNotSupport() {
        assertEquals(ToolingMode.LEGACY,
                ToolingMode.resolve(ToolingMode.NATIVE, false, true));
    }

    @Test
    void preferredNativeFallsBackToLegacyWhenNoDescriptors() {
        assertEquals(ToolingMode.LEGACY,
                ToolingMode.resolve(ToolingMode.NATIVE, true, false));
    }

    @Test
    void allConditionsMetReturnsNative() {
        assertEquals(ToolingMode.NATIVE,
                ToolingMode.resolve(ToolingMode.NATIVE, true, true));
    }
}

