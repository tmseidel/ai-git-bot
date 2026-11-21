package org.remus.giteabot.review.structured;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkflowOutputRepository extends JpaRepository<WorkflowOutput, Long> {

    Optional<WorkflowOutput> findByReference(String reference);

    /** Outputs of one workflow for one pull request, newest first. */
    @Query("SELECT o FROM WorkflowOutput o"
            + " WHERE o.gitIntegrationId = :gitIntegrationId"
            + " AND o.botId = :botId"
            + " AND LOWER(o.repoOwner) = LOWER(:repoOwner)"
            + " AND LOWER(o.repoName) = LOWER(:repoName)"
            + " AND o.prNumber = :prNumber"
            + " AND o.workflowKey = :workflowKey"
            + " ORDER BY o.id DESC")
    List<WorkflowOutput> findByScope(@Param("gitIntegrationId") Long gitIntegrationId,
                                     @Param("botId") Long botId,
                                     @Param("repoOwner") String repoOwner,
                                     @Param("repoName") String repoName,
                                     @Param("prNumber") Long prNumber,
                                     @Param("workflowKey") String workflowKey,
                                     Pageable pageable);
}
