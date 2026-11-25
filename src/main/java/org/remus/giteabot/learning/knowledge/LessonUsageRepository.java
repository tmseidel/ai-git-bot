package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonUsageRepository extends JpaRepository<LessonUsage, Long> {

    long countByLessonId(Long lessonId);

    long countByRevisionId(Long revisionId);

    boolean existsByInvocationIdAndRevisionId(String invocationId, Long revisionId);

    List<LessonUsage> findByInvocationIdOrderByIdAsc(String invocationId);
}
