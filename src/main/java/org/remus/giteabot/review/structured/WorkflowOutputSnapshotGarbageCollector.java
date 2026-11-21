package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention for learning evidence, mirroring the other {@code *GarbageCollector} jobs.
 *
 * <p>Only the bulky snapshot expires. The review record, its findings and the feedback that
 * references them are kept independently, so an expired snapshot can never orphan a finding or make
 * a published reference unresolvable.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowOutputSnapshotGarbageCollector {

    private final ReviewSnapshotService snapshotService;

    /** Cron-fired entry point; default 03:53 server time (staggered from the other GC jobs), tunable via learning.snapshot-gc-cron. */
    @Scheduled(cron = "${learning.snapshot-gc-cron:0 53 3 * * *}")
    public void runGarbageCollection() {
        int deleted = snapshotService.purgeExpired();
        if (deleted > 0) {
            log.info("WorkflowOutputSnapshotGarbageCollector: pruned {} learning snapshot(s)",
                    deleted);
        }
    }
}
