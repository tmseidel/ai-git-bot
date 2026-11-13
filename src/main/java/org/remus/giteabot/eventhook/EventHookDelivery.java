package org.remus.giteabot.eventhook;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One delivery attempt row per (endpoint, event). The table doubles as the
 * durable retry queue and the admin "recent deliveries" view.
 *
 * <p>{@link #endpointId} is a plain FK column (no JPA relation) so delivery
 * rows can safely cross {@code @Async} boundaries without lazy-loading
 * surprises; the worker re-loads the endpoint separately.
 *
 * <p>{@link #payloadJson} contains the event payload only — credentials are
 * never copied into deliveries.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "event_hook_delivery", uniqueConstraints =
        @UniqueConstraint(name = "uk_event_hook_delivery_endpoint_event", columnNames = {"endpoint_id", "event_key"}))
public class EventHookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Correlates the row with the {@code X-EventHook-Delivery} header and payload {@code id}. */
    @Column(nullable = false, length = 36)
    private String deliveryUuid;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    /** Wire value of the event type, e.g. {@code prworkflow.completed}. */
    @Column(nullable = false, length = 80)
    private String eventType;

    /** Stable logical event key for required deliveries; null for legacy publications. */
    @Column(name = "event_key", length = 256)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    private Instant nextAttemptAt;

    private Integer lastResponseCode;

    @Column(length = 2000)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
