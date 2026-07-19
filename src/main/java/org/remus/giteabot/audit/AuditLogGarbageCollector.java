package org.remus.giteabot.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Nightly garbage collector for audit events.
 *
 * <p>Deletes {@link PrAuditEvent} rows whose {@code createdAt} is older than
 * the configured retention window ({@code audit.retention}, default 90 days).
 * Runs on a fixed cron schedule ({@code audit.gc-cron}, default 04:23 server
 * time — chosen to sit outside the main webhook burst and the promoted-suite
 * GC window at 03:17).</p>
 */
@Slf4j
@Component
public class AuditLogGarbageCollector {

    private final PrAuditEventRepository auditRepository;
    private final Duration retention;
    /** Injectable "now" source — overridden by tests to avoid wall-clock sleeps. */
    private Supplier<Instant> nowSupplier = Instant::now;

    public AuditLogGarbageCollector(
            PrAuditEventRepository auditRepository,
            @Value("${audit.retention:P90D}") Duration retention) {
        this.auditRepository = auditRepository;
        this.retention = retention;
    }

    /** Test seam — replaces the wall-clock with a deterministic supplier. */
    void setNowSupplierForTest(Supplier<Instant> nowSupplier) {
        this.nowSupplier = nowSupplier;
    }

    /**
     * Cron-fired entry point. Default schedule: nightly at 04:23 server
     * time. Tunable via {@code audit.gc-cron}.
     */
    @Scheduled(cron = "${audit.gc-cron:0 23 4 * * *}")
    public void runGarbageCollection() {
        int deleted = collectOnce();
        if (deleted > 0) {
            log.info("AuditLogGarbageCollector: deleted {} audit event(s) older than retention={}",
                    deleted, retention);
        } else {
            log.debug("AuditLogGarbageCollector: no audit events past retention={}",
                    retention);
        }
    }

    /**
     * Single GC pass; returns the number of audit events deleted. Package-private
     * so tests can drive it deterministically without waiting for the cron.
     */
    int collectOnce() {
        Instant cutoff = nowSupplier.get().minus(retention);
        return auditRepository.deleteByCreatedAtBefore(cutoff);
    }
}
