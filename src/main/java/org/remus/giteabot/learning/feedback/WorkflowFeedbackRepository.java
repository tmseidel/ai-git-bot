package org.remus.giteabot.learning.feedback;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkflowFeedbackRepository extends JpaRepository<WorkflowFeedback, Long> {

    Optional<WorkflowFeedback> findByReference(String reference);

    Optional<WorkflowFeedback> findBySourceKey(String sourceKey);

    /** The actor's current assessment of one target, if any. */
    @Query("""
            SELECT f FROM WorkflowFeedback f
            WHERE f.outputId = :outputId
              AND f.targetType = :targetType
              AND ((:findingId IS NULL AND f.findingId IS NULL) OR f.findingId = :findingId)
              AND f.actor = :actor
              AND f.state = org.remus.giteabot.learning.feedback.FeedbackState.EFFECTIVE
            """)
    List<WorkflowFeedback> findEffective(@Param("outputId") Long outputId,
                                         @Param("targetType") FeedbackTargetType targetType,
                                         @Param("findingId") Long findingId,
                                         @Param("actor") String actor);

    @Query("""
            SELECT f FROM WorkflowFeedback f
            WHERE f.gitIntegrationId = :gitIntegrationId
              AND f.botId = :botId
              AND f.repoOwner = :repoOwner
              AND f.repoName = :repoName
              AND f.prNumber = :prNumber
              AND f.id < :beforeId
            ORDER BY f.id DESC
            """)
    List<WorkflowFeedback> pageForPullRequest(@Param("gitIntegrationId") Long gitIntegrationId,
                                              @Param("botId") Long botId,
                                              @Param("repoOwner") String repoOwner,
                                              @Param("repoName") String repoName,
                                              @Param("prNumber") Long prNumber,
                                              @Param("beforeId") long beforeId,
                                              Pageable pageable);

    @Query("""
            SELECT f FROM WorkflowFeedback f
            WHERE f.gitIntegrationId = :gitIntegrationId
              AND f.botId = :botId
              AND f.repoOwner = :repoOwner
              AND f.repoName = :repoName
              AND f.id < :beforeId
            ORDER BY f.id DESC
            """)
    List<WorkflowFeedback> pageForRepository(@Param("gitIntegrationId") Long gitIntegrationId,
                                             @Param("botId") Long botId,
                                             @Param("repoOwner") String repoOwner,
                                             @Param("repoName") String repoName,
                                             @Param("beforeId") long beforeId,
                                             Pageable pageable);
}
