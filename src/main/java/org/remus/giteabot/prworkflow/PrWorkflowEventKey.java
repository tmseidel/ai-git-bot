package org.remus.giteabot.prworkflow;

import org.remus.giteabot.audit.AuditEventType;

import java.util.Objects;

/** Stable local event identity, shared by its audit row and endpoint deliveries. */
public record PrWorkflowEventKey(AuditEventType eventType, Long runId, String findingId) {

    public PrWorkflowEventKey {
        Objects.requireNonNull(eventType, "eventType");
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("A persisted run id is required");
        }
        if (findingId != null && (findingId.isBlank() || findingId.length() > 128)) {
            throw new IllegalArgumentException("findingId must contain 1 to 128 characters");
        }
        if (eventType == AuditEventType.FINDING_POSTED && findingId == null) {
            throw new IllegalArgumentException("Finding events require a finding id");
        }
    }

    /** Wire/storage key; a missing finding id cannot collide with a valid finding id. */
    public String value() {
        return eventType.name() + ":" + runId + ":" + (findingId == null ? "" : findingId);
    }
}
