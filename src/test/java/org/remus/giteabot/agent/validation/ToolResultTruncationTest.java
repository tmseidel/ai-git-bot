package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.util.ProcessSupport;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTruncationTest {
    @Test
    void captureDistinguishesExactLimitFromDiscardedBytes() throws Exception {
        var exact = ProcessSupport.waitFor(new ProcessBuilder("sh", "-c", "printf abcd").start(), 5, TimeUnit.SECONDS, 4);
        var cut = ProcessSupport.waitFor(new ProcessBuilder("sh", "-c", "printf abcde").start(), 5, TimeUnit.SECONDS, 4);
        assertThat(exact.outputTruncated()).isFalse();
        assertThat(cut.output()).isEqualTo("abcd");
        assertThat(cut.outputTruncated()).isTrue();
        assertThat(new ToolResult(true, 0, cut.output(), "", cut.outputTruncated()).formatForAi())
                .contains("not complete evidence");
    }
}
