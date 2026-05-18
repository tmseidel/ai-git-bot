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
}

