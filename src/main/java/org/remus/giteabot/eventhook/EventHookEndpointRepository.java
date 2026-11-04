package org.remus.giteabot.eventhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventHookEndpointRepository extends JpaRepository<EventHookEndpoint, Long> {

    List<EventHookEndpoint> findByEnabledTrue();

    /**
     * The row a configuration document means when it names an endpoint. The column does not
     * make the name unique, so a hand-created duplicate resolves to the oldest row rather than
     * making the import fail or create a third one.
     */
    Optional<EventHookEndpoint> findFirstByNameOrderByIdAsc(String name);
}
