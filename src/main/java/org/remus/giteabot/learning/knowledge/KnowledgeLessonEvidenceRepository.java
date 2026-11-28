package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KnowledgeLessonEvidenceRepository extends JpaRepository<KnowledgeLessonEvidence, Long> {

    List<KnowledgeLessonEvidence> findByRevisionIdOrderByIdAsc(Long revisionId);

    List<KnowledgeLessonEvidence> findByRevisionIdInOrderByIdAsc(Collection<Long> revisionIds);

    List<KnowledgeLessonEvidence> findByFeedbackId(Long feedbackId);

    List<KnowledgeLessonEvidence> findByTargetReferenceInOrderByIdAsc(Collection<String> targets);
}
