package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Repository-mock tests for the GC decision logic (cutoff computation, keep
 * semantics, per-endpoint independence). The status filter that exempts
 * PENDING/RETRYING rows lives in the JPQL of {@code deleteTerminalBefore},
 * which Spring Data validates at context boot ({@code AiGitBotApplicationTests}).
 */
@ExtendWith(MockitoExtension.class)
class EventHookDeliveryGarbageCollectorTest {

    @Mock
    private EventHookEndpointRepository endpointRepository;
    @Mock
    private EventHookDeliveryRepository deliveryRepository;

    private EventHookProperties properties;
    private EventHookDeliveryGarbageCollector collector;

    @BeforeEach
    void setUp() {
        properties = new EventHookProperties();
        properties.getRetention().setKeepLast(10);
        collector = new EventHookDeliveryGarbageCollector(endpointRepository, deliveryRepository, properties);
    }

    private EventHookEndpoint endpoint(long id) {
        EventHookEndpoint endpoint = new EventHookEndpoint();
        endpoint.setId(id);
        return endpoint;
    }

    private EventHookDelivery delivery(long id, DeliveryStatus status) {
        EventHookDelivery delivery = new EventHookDelivery();
        delivery.setId(id);
        delivery.setStatus(status);
        return delivery;
    }

    /** Newest-first page of n deliveries with descending ids starting at highestId. */
    private List<EventHookDelivery> newestFirstPage(long highestId, int n) {
        List<EventHookDelivery> page = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            page.add(delivery(highestId - i, i % 2 == 0 ? DeliveryStatus.SUCCESS : DeliveryStatus.FAILED));
        }
        return page;
    }

    @Test
    void collectOnce_15TerminalDeliveriesKeep10_deletes5Oldest() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        // Newest 10 of ids 15..1 → page ids 15..6, cutoff = 6
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 10))))
                .thenReturn(newestFirstPage(15L, 10));
        when(deliveryRepository.deleteTerminalBefore(1L, 6L)).thenReturn(5);

        int deleted = collector.collectOnce();

        assertEquals(5, deleted);
        verify(deliveryRepository).deleteTerminalBefore(1L, 6L);
    }

    @Test
    void collectOnce_exactlyKeepDeliveries_deletesNothingBeyondCutoff() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 10))))
                .thenReturn(newestFirstPage(10L, 10));
        when(deliveryRepository.deleteTerminalBefore(1L, 1L)).thenReturn(0);

        int deleted = collector.collectOnce();

        assertEquals(0, deleted);
        verify(deliveryRepository).deleteTerminalBefore(1L, 1L);
    }

    @Test
    void collectOnce_shortPage_skipsDelete() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        // Only 4 rows exist (page shorter than keep=10) — nothing can be beyond the window.
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 10))))
                .thenReturn(newestFirstPage(4L, 4));

        int deleted = collector.collectOnce();

        assertEquals(0, deleted);
        verify(deliveryRepository, never()).deleteTerminalBefore(anyLong(), anyLong());
    }

    @Test
    void collectOnce_keepZero_deletesAllTerminalRows() {
        properties.getRetention().setKeepLast(0);
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        // keep=0 → page of 1 (the single newest), cutoff = newest id + 1
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(delivery(20L, DeliveryStatus.SUCCESS)));
        when(deliveryRepository.deleteTerminalBefore(1L, 21L)).thenReturn(20);

        int deleted = collector.collectOnce();

        assertEquals(20, deleted);
        verify(deliveryRepository).deleteTerminalBefore(1L, 21L);
    }

    @Test
    void collectOnce_inflightRowsCountTowardWindow_cutoffIsRetryingRowId() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        // Newest-10 window where the 10th-newest is a RETRYING row (id 6): it counts
        // toward the window, so the cutoff is its id — terminal rows older than id 6 go.
        List<EventHookDelivery> page = newestFirstPage(15L, 9);
        page.add(delivery(6L, DeliveryStatus.RETRYING));
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 10))))
                .thenReturn(page);
        when(deliveryRepository.deleteTerminalBefore(1L, 6L)).thenReturn(3);

        int deleted = collector.collectOnce();

        assertEquals(3, deleted);
        verify(deliveryRepository).deleteTerminalBefore(1L, 6L);
    }

    @Test
    void collectOnce_twoEndpoints_prunedIndependently() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L), endpoint(2L)));
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), eq(PageRequest.of(0, 10))))
                .thenReturn(newestFirstPage(15L, 10));
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(2L), eq(PageRequest.of(0, 10))))
                .thenReturn(newestFirstPage(3L, 3)); // short page → skip
        when(deliveryRepository.deleteTerminalBefore(1L, 6L)).thenReturn(5);

        int deleted = collector.collectOnce();

        assertEquals(5, deleted);
        verify(deliveryRepository).deleteTerminalBefore(1L, 6L);
        verify(deliveryRepository, never()).deleteTerminalBefore(eq(2L), anyLong());
    }

    @Test
    void collectOnce_noDeliveries_skipsDelete() {
        when(endpointRepository.findAll()).thenReturn(List.of(endpoint(1L)));
        when(deliveryRepository.findByEndpointIdOrderByIdDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of());

        int deleted = collector.collectOnce();

        assertEquals(0, deleted);
        verify(deliveryRepository, never()).deleteTerminalBefore(anyLong(), anyLong());
    }
}
