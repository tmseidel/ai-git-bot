package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalModelAttributesTest {

    @Test
    void appVersion_returnsApplicationVersion() {
        ApplicationVersion applicationVersion = mock(ApplicationVersion.class);
        when(applicationVersion.get()).thenReturn("1.19.0");

        assertEquals("1.19.0", new GlobalModelAttributes(applicationVersion).appVersion());
    }
}
