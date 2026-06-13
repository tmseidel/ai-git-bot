package org.remus.giteabot.aiusage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AiErrorLogRepository extends JpaRepository<AiErrorLog, Long> {

    Page<AiErrorLog> findByTimestampBetween(Instant from, Instant to, Pageable pageable);

    Page<AiErrorLog> findAllByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to, Pageable pageable);

    long countByTimestampAfter(Instant after);
}
