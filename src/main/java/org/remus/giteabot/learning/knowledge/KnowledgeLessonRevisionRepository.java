package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeLessonRevisionRepository extends JpaRepository<KnowledgeLessonRevision, Long> {

    List<KnowledgeLessonRevision> findByLessonIdOrderByRevisionNumberAsc(Long lessonId);

    List<KnowledgeLessonRevision> findByLessonIdInOrderByLessonIdAscRevisionNumberAsc(Collection<Long> lessonIds);

    Optional<KnowledgeLessonRevision> findByLessonIdAndRevisionNumber(Long lessonId, int revisionNumber);
}
