package org.remus.giteabot.aiusage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AiErrorLogRepository extends JpaRepository<AiErrorLog, Long> {

    Page<AiErrorLog> findByTimestampBetween(Instant from, Instant to, Pageable pageable);

    List<AiErrorLog> findAllByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);

    long countByTimestampAfter(Instant after);
}
