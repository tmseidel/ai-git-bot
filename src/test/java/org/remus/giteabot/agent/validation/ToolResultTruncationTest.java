package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.util.ProcessSupport;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTruncationTest {
    @ParameterizedTest
    @CsvSource({"4, abcd, abcd, false", "4, abcde, abcd, true", "0, abcd, '', true",
            "0, '', '', false", "5, a一b, a一b, false", "5, a一bc, a一b, true", "2, a一, a, true"})
    void captureMarksOnlyDiscardedBytesAndKeepsValidUtf8(int limit, String text, String retained,
                                                       boolean truncated) throws Exception {
        var result = ProcessSupport.waitFor(new ProcessBuilder("sh", "-c", "printf '%s' \"$1\"",
                "capture", text).start(), 5, TimeUnit.SECONDS, limit);

        assertThat(result.finished()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(retained);
        assertThat(result.outputTruncated()).isEqualTo(truncated);
        assertThat(new ToolResult(true, 0, result.output(), "", result.outputTruncated()).formatForAi()
                .contains("not complete evidence")).isEqualTo(truncated);
    }

    @Test
    void compatibilityConstructorsDoNotClaimCompleteOutputWasTruncated() {
        ToolResult tool = new ToolResult(true, 0, "complete output", "");

        assertThat(tool.outputTruncated()).isFalse();
        assertThat(tool.formatForAi()).contains("complete output").doesNotContain("not complete evidence");
        assertThat(new ProcessSupport.CommandResult(true, 0, "complete output").outputTruncated()).isFalse();
        assertThat(new PythonExecutionOutcome(true, 0, "complete output", "").outputTruncated()).isFalse();
    }
}
