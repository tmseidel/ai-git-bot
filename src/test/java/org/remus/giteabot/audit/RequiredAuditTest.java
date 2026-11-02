package org.remus.giteabot.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RequiredAuditTest {
    @Test
    void requiredWritesPropagateFailureWhileExistingBestEffortCallersDoNot() {
        var repository = mock(PrAuditEventRepository.class);
        var service = new PrAuditEventService(repository);
        when(repository.save(any())).thenThrow(new IllegalStateException("Database unavailable"));
        var event = PrAuditEvent.builder().eventType(AuditEventType.PR_WORKFLOW_SHADOW_RECORDED).build();
        assertThatIllegalStateException().isThrownBy(() -> service.recordRequired(event));
        assertThatCode(() -> service.record(event)).doesNotThrowAnyException();
    }
}
