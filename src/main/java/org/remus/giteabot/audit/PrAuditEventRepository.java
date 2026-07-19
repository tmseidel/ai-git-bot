package org.remus.giteabot.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface PrAuditEventRepository extends JpaRepository<PrAuditEvent, Long> {

    List<PrAuditEvent> findByRunIdOrderByIdAsc(Long runId);

    List<PrAuditEvent> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdAsc(
            Long botId, String repoOwner, String repoName, Long prNumber);

    List<PrAuditEvent> findByBotIdOrderByIdAsc(Long botId);

    /** Returns the most recent event for a run (for previous-hash lookup). */
    PrAuditEvent findTopByRunIdOrderByIdDesc(Long runId);

    /** Deletes all audit events older than the given cutoff. Returns the count of deleted rows. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PrAuditEvent e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
