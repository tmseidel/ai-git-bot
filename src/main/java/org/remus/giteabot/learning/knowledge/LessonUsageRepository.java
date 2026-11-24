package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonUsageRepository extends JpaRepository<LessonUsage, Long> {

    long countByLessonId(Long lessonId);

    long countByRevisionId(Long revisionId);
}
