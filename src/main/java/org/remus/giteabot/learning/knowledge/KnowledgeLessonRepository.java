package org.remus.giteabot.learning.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeLessonRepository extends JpaRepository<KnowledgeLesson, Long> {

    Optional<KnowledgeLesson> findByReference(String reference);

    /** One page of a repository's lessons, newest first; {@code states} narrows the listing. */
    @Query("""
            SELECT l FROM KnowledgeLesson l
            WHERE l.gitIntegrationId = :gitIntegrationId
              AND l.botId = :botId
              AND LOWER(l.repoOwner) = LOWER(:repoOwner)
              AND LOWER(l.repoName) = LOWER(:repoName)
              AND l.workflowKey = :workflowKey
              AND l.state IN :states
              AND l.id < :beforeId
            ORDER BY l.id DESC
            """)
    List<KnowledgeLesson> page(@Param("gitIntegrationId") Long gitIntegrationId,
                               @Param("botId") Long botId,
                               @Param("repoOwner") String repoOwner,
                               @Param("repoName") String repoName,
                               @Param("workflowKey") String workflowKey,
                               @Param("states") Collection<LessonState> states,
                               @Param("beforeId") long beforeId,
                               Pageable pageable);
}
