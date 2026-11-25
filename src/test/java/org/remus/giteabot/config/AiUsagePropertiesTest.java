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
                .contains("clear it regularly on the Workflow runs page");
    }

    @Test
    void staysQuietWithALimitOrWithoutCapture(CapturedOutput output) {
        properties(true, 65535).warnIfPayloadsAreUnbounded();
        properties(false, 0).warnIfPayloadsAreUnbounded();

        assertThat(output).doesNotContain("AI usage raw payloads are stored in full");
    }

    @Test
    void zeroOrNegativeMaxLengthMeansNoTruncation() {
        AiUsageProperties properties = new AiUsageProperties();
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(65535);

        properties.setMaxRawPayloadLength(0);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(Integer.MAX_VALUE);
        properties.setMaxRawPayloadLength(-1);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(Integer.MAX_VALUE);
        // the old 65535 cap is gone: the columns are TEXT
        properties.setMaxRawPayloadLength(500_000);
        assertThat(properties.getEffectiveMaxRawPayloadLength()).isEqualTo(500_000);
    }
}
