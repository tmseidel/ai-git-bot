package org.remus.giteabot.eventhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.PrWorkflowEventKey;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single entry point for outgoing-webhook publication, called from
 * workflow/agent code. Filters enabled endpoints by event-type subscription
 * and scope, persists one {@link EventHookDelivery} row per matching endpoint
 * (durable, cheap, inside the caller's transaction), then hands the row id to
 * the {@link EventHookDeliveryWorker} for asynchronous dispatch.
 *
 * <p>The legacy {@link #publish} entry point is best-effort. Required completion
 * events instead use {@link #enqueueRequired}, which aborts on persistence errors
 * and only kicks the existing worker after commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookPublisher {

    private final EventHookProperties properties;
    private final EventHookEndpointRepository endpointRepository;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker deliveryWorker;
    private final ObjectMapper objectMapper;
    private final PrWorkflowRunRepository runRepository;

    /**
     * Joins the caller's transaction and locks the run before any keyed insert.
     * Existing endpoint rows are immutable, including their UUID/timestamp and target set.
     * A changed payload under the same key is an error, not a second publication.
     * Only matching PR lifecycle and keyed finding hooks are supported by the current catalog;
     * audit-only review events and issue-assignment hooks are not interchangeable with them.
     * Delivery remains at-least-once; receivers can deduplicate {@code eventKey}.
     * Dedupe/conflict detection lasts only while the delivery rows are retained. Completion
     * owners must first call the generic completion gate in the same outer transaction and
     * return on {@code applied=false} before calling this helper for domain events.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<EventHookDelivery> enqueueRequired(PrWorkflowEventKey key, EventHookEventType type,
                                                   Bot bot, Map<String, Object> data) {
        validateRequiredType(key, type);
        Objects.requireNonNull(bot, "bot");
        PrWorkflowRun run = runRepository.findByIdForUpdate(key.runId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + key.runId()));
        if (!Objects.equals(run.getBotId(), bot.getId())) {
            throw new IllegalArgumentException("Required delivery does not belong to the bot");
        }
        // shortcut: retained rows alone deduplicate here; domain owners must gate terminal replays before this helper.
        List<EventHookDelivery> existing = deliveryRepository.findByEventKeyOrderByEndpointIdAsc(key.value());
        for (EventHookDelivery delivery : existing) {
            EventHookPayload stored = objectMapper.readValue(delivery.getPayloadJson(), EventHookPayload.class);
            String expected = requiredJson(key, type, bot, run, data, delivery.getDeliveryUuid(), stored.timestamp());
            if (!type.wireValue().equals(delivery.getEventType())
                    || !objectMapper.readTree(expected).equals(objectMapper.readTree(delivery.getPayloadJson()))) {
                throw new IllegalStateException("Conflicting required delivery: " + key.value());
            }
        }
        if (!existing.isEmpty() || !properties.isEnabled()) {
            return existing;
        }
        List<EventHookDelivery> created = new ArrayList<>();
        for (EventHookEndpoint endpoint : endpointRepository.findByEnabledTrue()) {
            if (!endpoint.isSubscribedTo(type)
                    || !endpoint.matchesScope(bot.getId(), run.getRepoOwner(), run.getRepoName())) {
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            String json = requiredJson(key, type, bot, run, data, uuid, Instant.now());
            if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
                throw new IllegalArgumentException("Required webhook payload exceeds the configured byte limit");
            }
            EventHookDelivery delivery = new EventHookDelivery();
            delivery.setDeliveryUuid(uuid);
            delivery.setEndpointId(endpoint.getId());
            delivery.setEventKey(key.value());
            delivery.setEventType(type.wireValue());
            delivery.setPayloadJson(json);
            delivery = deliveryRepository.saveAndFlush(delivery);
            created.add(delivery);
            dispatchAfterCommit(delivery.getId());
        }
        return List.copyOf(created);
    }

    private static void validateRequiredType(PrWorkflowEventKey key, EventHookEventType type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        boolean supported = switch (key.eventType()) {
            case PR_WORKFLOW_RUN_STARTED -> key.findingId() == null && type == EventHookEventType.PR_WORKFLOW_STARTED;
            case PR_WORKFLOW_RUN_COMPLETED -> key.findingId() == null
                    && (type == EventHookEventType.PR_WORKFLOW_COMPLETED || type == EventHookEventType.PR_WORKFLOW_FAILED);
            case FINDING_POSTED -> type == EventHookEventType.AGENT_REVIEW_FINDING_DETECTED;
            default -> false;
        };
        if (!supported) {
            throw new IllegalArgumentException("Unsupported required event pairing: " + key.eventType() + " / " + type);
        }
    }

    private String requiredJson(PrWorkflowEventKey key, EventHookEventType type, Bot bot,
                                PrWorkflowRun run, Map<String, Object> data, String uuid, Instant timestamp) {
        return objectMapper.writeValueAsString(EventHookPayload.required(key.value(), uuid, timestamp,
                type, bot, run.getRepoOwner(), run.getRepoName(), run.getPrNumber(), data));
    }

    private void dispatchAfterCommit(Long deliveryId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    deliveryWorker.deliverAsync(deliveryId);
                } catch (RuntimeException e) {
                    // The committed PENDING row is still recoverable by the existing sweeper.
                    log.warn("Cannot dispatch committed required delivery {}: {}", deliveryId, e.getMessage());
                }
            }
        });
    }

    public void publish(EventHookEventType type, Bot bot, String repoOwner, String repoName,
                        Long prNumber, Long issueNumber, Map<String, Object> data) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            for (EventHookEndpoint endpoint : endpointRepository.findByEnabledTrue()) {
                if (!endpoint.isSubscribedTo(type)
                        || !endpoint.matchesScope(bot.getId(), repoOwner, repoName)) {
                    continue;
                }
                String uuid = UUID.randomUUID().toString();
                EventHookPayload payload = EventHookPayload.of(uuid, type, bot, repoOwner,
                        repoName, prNumber, issueNumber, data);
                String json = objectMapper.writeValueAsString(payload);
                if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
                    log.warn("Skipping oversized webhook payload ({} bytes) for {}", json.length(), type);
                    continue;
                }
                EventHookDelivery delivery = new EventHookDelivery();
                delivery.setDeliveryUuid(uuid);
                delivery.setEndpointId(endpoint.getId());
                delivery.setEventType(type.wireValue());
                delivery.setPayloadJson(json);
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery = deliveryRepository.save(delivery);
                // @Async on a separate bean — self-invocation would not be proxied.
                deliveryWorker.deliverAsync(delivery.getId());
            }
        } catch (Exception e) {
            log.error("Failed to publish event-hook event {}: {}", type, e.getMessage(), e);
        }
    }
}
