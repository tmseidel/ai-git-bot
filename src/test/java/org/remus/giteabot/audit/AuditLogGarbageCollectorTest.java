package org.remus.giteabot.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogGarbageCollectorTest {

    private PrAuditEventRepository auditRepo;
    private AuditLogGarbageCollector gc;
    private Instant fixedNow;

    @BeforeEach
    void setUp() {
        auditRepo = mock(PrAuditEventRepository.class);
        fixedNow = Instant.parse("2026-07-19T12:00:00Z");
        gc = new AuditLogGarbageCollector(auditRepo, Duration.ofDays(90));
        Supplier<Instant> clock = () -> fixedNow;
        gc.setNowSupplierForTest(clock);
    }

    @Test
    void noStaleEvents_returnsZero() {
        when(auditRepo.deleteByCreatedAtBefore(any())).thenReturn(0);

        assertThat(gc.collectOnce()).isZero();
    }

    @Test
    void usesRetentionWindowAsCutoff() {
        when(auditRepo.deleteByCreatedAtBefore(any())).thenReturn(0);

        gc.collectOnce();

        Instant expectedCutoff = fixedNow.minus(Duration.ofDays(90));
        verify(auditRepo).deleteByCreatedAtBefore(expectedCutoff);
    }

    @Test
    void deletesStaleEventsAndReturnsCount() {
        Instant cutoff = fixedNow.minus(Duration.ofDays(90));
        when(auditRepo.deleteByCreatedAtBefore(cutoff)).thenReturn(42);

        assertThat(gc.collectOnce()).isEqualTo(42);
    }
}
