package org.remus.giteabot.review.structured;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    Optional<ReviewRecord> findByOutputId(Long outputId);

    /** The review of an output, row-locked until the transaction ends: serializes its deliveries. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReviewRecord r where r.output.id = :outputId")
    Optional<ReviewRecord> findForUpdateByOutputId(@Param("outputId") Long outputId);

    /**
     * Finalized reviews of the same bot and pull request that were created before the output
     * {@code outputId}, newest first — the first one is the review a new review is matched against.
     */
    @Query("select r from ReviewRecord r join r.output o where o.gitIntegrationId = :integration "
            + "and o.botId = :bot and lower(o.repoOwner) = lower(:owner) and lower(o.repoName) = lower(:repo) "
            + "and o.prNumber = :pr and o.workflowKey = :key and o.id < :outputId "
            + "and r.state = org.remus.giteabot.review.structured.ReviewState.FINALIZED "
            + "order by o.id desc")
    List<ReviewRecord> findEarlierFinalized(@Param("integration") Long integration, @Param("bot") Long bot,
                                            @Param("owner") String owner, @Param("repo") String repo,
                                            @Param("pr") Long pr, @Param("key") String key,
                                            @Param("outputId") Long outputId, Pageable page);
}
