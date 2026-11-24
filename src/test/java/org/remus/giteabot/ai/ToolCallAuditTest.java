package org.remus.giteabot.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallAuditTest {

    private final List<AuditRun.ToolCallRecord> recorded = new ArrayList<>();

    private final AuditRun.Sink sink = new AuditRun.Sink() {
        @Override
        public void runFinished(AuditRun run) {
        }

        @Override
        public void toolCallFinished(AuditRun run, AuditRun.ToolCallRecord record) {
            recorded.add(record);
        }
    };

    @AfterEach
    void tearDown() {
        AiAuditContext.clear();
    }

    private AuditRun runWithInteraction() {
        AuditRun run = AiAuditContext.openRun("review", null);
        run.attach(1L, sink);
        run.beginAttempt();
        run.setInteractionId(10L);
        return run;
    }


    @Test
    void textErrorsAndExceptionsAreRecordedAsFailures() {
        runWithInteraction();

        ToolCallAudit.record(ToolCallAudit.BUILT_IN, "doc-write", "{}",
                () -> "ERROR: path outside docs", ToolCallAudit::errorOfTextResult);
        assertThatThrownBy(() -> ToolCallAudit.record(ToolCallAudit.BUILT_IN, "grep", "{}",
                () -> { throw new IllegalStateException("boom"); }, r -> null))
                .hasMessage("boom");

        assertThat(recorded).extracting(AuditRun.ToolCallRecord::errorMessage)
                .containsExactly("path outside docs", "boom");
        assertThat(recorded).noneMatch(AuditRun.ToolCallRecord::success);
    }

    @Test
    void callsOutsideAPersistedRunAreNotRecorded() {
        assertThat(ToolCallAudit.record(ToolCallAudit.BUILT_IN, "grep", "{}", () -> "ok", r -> null))
                .isEqualTo("ok");
        AiAuditContext.openRun("review", null);
        ToolCallAudit.record(ToolCallAudit.BUILT_IN, "grep", "{}", () -> "ok", r -> null);

        assertThat(recorded).isEmpty();
    }
}
