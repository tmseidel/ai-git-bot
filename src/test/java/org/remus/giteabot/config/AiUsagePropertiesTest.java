package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AiUsagePropertiesTest {

    private static AiUsageProperties properties(boolean enabled, int maxLength) {
        AiUsageProperties properties = new AiUsageProperties();
        properties.setRawPayloadsEnabled(enabled);
        properties.setMaxRawPayloadLength(maxLength);
        return properties;
    }

    @Test
    void warnsAtStartupWhenPayloadsAreStoredInFull(CapturedOutput output) {
        properties(true, 0).warnIfPayloadsAreUnbounded();

        assertThat(output).contains("AI usage raw payloads are stored in full")
                .contains("clear it regularly on the Usage page");
    }

    @Test
    void staysQuietWithALimitOrWithoutCapture(CapturedOutput output) {
        properties(true, 65535).warnIfPayloadsAreUnbounded();
        properties(false, 0).warnIfPayloadsAreUnbounded();

        assertThat(output).doesNotContain("AI usage raw payloads are stored in full");
    }
}
