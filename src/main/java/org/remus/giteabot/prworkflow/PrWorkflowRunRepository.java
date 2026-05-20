package org.remus.giteabot.prworkflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrWorkflowRunRepository extends JpaRepository<PrWorkflowRun, Long> {

    List<PrWorkflowRun> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
            Long botId,
            String repoOwner,
            String repoName,
            Long prNumber,
            String workflowKey,
            List<PrWorkflowRunStatus> statuses);

    /**
     * Lists runs currently in the given status, ordered by oldest first.
     * Used by {@link org.remus.giteabot.prworkflow.deployment.CiActionPoller}
     * to fan-out polling across in-flight {@code CI_ACTION} deployments.
     */
    List<PrWorkflowRun> findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus status);
}

