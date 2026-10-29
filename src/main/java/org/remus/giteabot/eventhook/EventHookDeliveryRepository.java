package org.remus.giteabot.eventhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventHookDeliveryRepository extends JpaRepository<EventHookDelivery, Long> {

    List<EventHookDelivery> findByEventKeyOrderByEndpointIdAsc(String eventKey);

    @Query("SELECT d FROM EventHookDelivery d WHERE d.status = org.remus.giteabot.eventhook.DeliveryStatus.PENDING "
            + "OR (d.status = org.remus.giteabot.eventhook.DeliveryStatus.RETRYING AND d.nextAttemptAt <= :now) "
            + "ORDER BY d.id ASC")
    List<EventHookDelivery> findDueDeliveries(@Param("now") Instant now, Pageable pageable);

    List<EventHookDelivery> findTop50ByEndpointIdOrderByIdDesc(Long endpointId);

    /** Newest deliveries for an endpoint regardless of status; use PageRequest.of(0, keep) to find the keep-cutoff. */
    List<EventHookDelivery> findByEndpointIdOrderByIdDesc(Long endpointId, Pageable pageable);

    /** Deletes terminal rows (SUCCESS/FAILED) older than the keep-cutoff. PENDING/RETRYING are never touched. */
    @Modifying
    @Query("DELETE FROM EventHookDelivery d WHERE d.endpointId = :endpointId "
            + "AND d.status IN (org.remus.giteabot.eventhook.DeliveryStatus.SUCCESS, "
            + "org.remus.giteabot.eventhook.DeliveryStatus.FAILED) AND d.id < :cutoffId")
    int deleteTerminalBefore(@Param("endpointId") Long endpointId, @Param("cutoffId") Long cutoffId);
}
