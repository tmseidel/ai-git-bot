package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LessonUsageRepository extends JpaRepository<LessonUsage, Long> {

    /** Usage counts of the lessons' revisions in one query, as {@code [revisionId, count]} rows. */
    @Query("select u.revisionId, count(u) from LessonUsage u where u.lessonId in :lessonIds "
            + "group by u.revisionId")
    List<Object[]> countPerRevision(@Param("lessonIds") Collection<Long> lessonIds);

    boolean existsByInvocationIdAndRevisionId(String invocationId, Long revisionId);
}
