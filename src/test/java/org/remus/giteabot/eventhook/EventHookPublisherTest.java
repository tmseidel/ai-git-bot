package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventHookPublisherTest {

    @Mock
    private EventHookEndpointRepository endpointRepository;
    @Mock
    private EventHookDeliveryRepository deliveryRepository;
    @Mock
    private EventHookDeliveryWorker deliveryWorker;
    @Mock
    private PrWorkflowRunRepository runRepository;

    private EventHookProperties properties;
    private EventHookPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new EventHookProperties();
        publisher = new EventHookPublisher(properties, endpointRepository, deliveryRepository,
                deliveryWorker, new ObjectMapper(), runRepository);
        lenient().when(deliveryRepository.save(any(EventHookDelivery.class))).thenAnswer(inv -> {
            EventHookDelivery d = inv.getArgument(0);
            d.setId(42L);
            return d;
        });
    }

    private Bot bot() {
        Bot bot = new Bot();
        bot.setId(3L);
        bot.setName("review-bot");
        return bot;
    }

    private EventHookEndpoint subscribedEndpoint() {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setId(1L);
        endpoint.setEnabled(true);
        endpoint.setEventTypes("PR_WORKFLOW_COMPLETED");
        return endpoint;
    }

    private void publishCompleted() {
        publisher.publish(EventHookEventType.PR_WORKFLOW_COMPLETED, bot(), "acme", "shop",
                42L, null, Map.of("runId", 128));
    }

    @Test
    void publish_disabled_noRepositoryInteraction() {
        properties.setEnabled(false);

        publishCompleted();

        verifyNoInteractions(endpointRepository, deliveryRepository, deliveryWorker);
    }

    @Test
    void publish_subscribedEndpoint_savesDeliveryAndDispatches() {
        when(endpointRepository.findByEnabledTrue()).thenReturn(List.of(subscribedEndpoint()));

        publishCompleted();

        ArgumentCaptor<EventHookDelivery> captor = ArgumentCaptor.forClass(EventHookDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        EventHookDelivery saved = captor.getValue();
        assertEquals(1L, saved.getEndpointId());
        assertEquals("prworkflow.completed", saved.getEventType());
        assertEquals(DeliveryStatus.PENDING, saved.getStatus());
        assertNotNull(saved.getDeliveryUuid());
        assertTrue(saved.getPayloadJson().contains("\"eventType\":\"prworkflow.completed\""));
        assertTrue(saved.getPayloadJson().contains("\"runId\":128"));
        verify(deliveryWorker).deliverAsync(42L);
    }

    @Test
    void publish_subscribedToDifferentType_skipped() {
        when(endpointRepository.findByEnabledTrue()).thenReturn(List.of(subscribedEndpoint()));

        publisher.publish(EventHookEventType.PR_WORKFLOW_STARTED, bot(), "acme", "shop",
                42L, null, Map.of());

        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void publish_scopeMismatch_skipped() {
        EventHookEndpoint endpoint = subscribedEndpoint();
        endpoint.setBotId(99L);
        when(endpointRepository.findByEnabledTrue()).thenReturn(List.of(endpoint));

        publishCompleted();

        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void publish_oversizedPayload_skipped() {
        properties.setMaxPayloadBytes(10);
        when(endpointRepository.findByEnabledTrue()).thenReturn(List.of(subscribedEndpoint()));

        publishCompleted();

        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(deliveryWorker);
    }

    @Test
    void publish_repositoryThrows_doesNotPropagate() {
        when(endpointRepository.findByEnabledTrue()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(this::publishCompleted);
    }
}
