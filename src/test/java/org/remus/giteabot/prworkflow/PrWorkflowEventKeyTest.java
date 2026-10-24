package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.audit.AuditEventType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrWorkflowEventKeyTest {

    @Test
    void keysAreStableAndSeparateTypesRunsAndFindings() {
        assertThat(new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, 42L, null).value())
                .isEqualTo("REVIEW_COMPLETED:42:");
        assertThat(new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 42L, "F1").value())
                .isEqualTo("FINDING_POSTED:42:F1")
                .isNotEqualTo(new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 42L, "F2").value())
                .isNotEqualTo(new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 43L, "F1").value());
    }

    @Test
    void findingEventsCannotUseAMissingOrAmbiguousFindingIdentity() {
        assertThatThrownBy(() -> new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 42L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 42L, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrWorkflowEventKey(AuditEventType.FINDING_POSTED, 42L, "F".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrWorkflowEventKey(AuditEventType.REVIEW_COMPLETED, 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
