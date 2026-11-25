package org.remus.giteabot.workflowrun;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.aiusage.AiInteraction;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Builds the query of the Workflow runs page from a {@link WorkflowRunFilter}. */
final class WorkflowRunSpecifications {

    private WorkflowRunSpecifications() {
    }

    static Specification<WorkflowRun> matching(WorkflowRunFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.runId() != null) {
                predicates.add(cb.equal(root.get("id"), filter.runId()));
            }
            if (filter.project() != null && !filter.project().isBlank()) {
                // Compared as the whole path: where owner and name meet is not unique. GitLab
                // subgroups put the extra slashes in the owner, Azure DevOps (project/repository)
                // in the name, and legacy runs had to guess from "owner/repo#n".
                predicates.add(cb.equal(
                        cb.concat(cb.concat(root.get("repoOwner"), "/"), root.get("repoName")),
                        filter.project().strip()));
            }
            if (filter.number() != null) {
                predicates.add(cb.equal(root.get("triggerNumber"), filter.number()));
            }
            if (filter.workflowType() != null && !filter.workflowType().isBlank()) {
                predicates.add(cb.equal(root.get("workflowType"), filter.workflowType()));
            }
            if (filter.botId() != null) {
                predicates.add(cb.equal(root.get("botId"), filter.botId()));
            }
            if (filter.state() != null) {
                predicates.add(cb.equal(root.get("state"), filter.state()));
            }
            if (filter.startedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), filter.startedFrom()));
            }
            if (filter.startedUntil() != null) {
                predicates.add(cb.lessThan(root.get("startedAt"), filter.startedUntil()));
            }
            if (filter.onlyWarnings()) {
                // Output truncated is derived: any interaction of the run stopped at max_tokens.
                Subquery<Long> truncated = query.subquery(Long.class);
                Root<AiInteraction> interaction = truncated.from(AiInteraction.class);
                truncated.select(interaction.get("id")).where(
                        cb.equal(interaction.get("workflowRun"), root),
                        cb.equal(interaction.get("stopReason"), StopReason.MAX_TOKENS.name()));
                predicates.add(cb.or(cb.isTrue(root.get("roundLimitReached")), cb.exists(truncated)));
            }
            if (filter.onlyFailedCalls()) {
                // A failed AI call: a failed interaction that is the last attempt of its round,
                // so a failure a retry recovered from does not count.
                Subquery<Long> failed = query.subquery(Long.class);
                Root<AiInteraction> interaction = failed.from(AiInteraction.class);
                Subquery<Long> later = failed.subquery(Long.class);
                Root<AiInteraction> retry = later.from(AiInteraction.class);
                later.select(retry.get("id")).where(
                        cb.equal(retry.get("workflowRun"), interaction.get("workflowRun")),
                        cb.equal(retry.get("roundNumber"), interaction.get("roundNumber")),
                        cb.greaterThan(retry.get("attempt"), interaction.<Integer>get("attempt")));
                failed.select(interaction.get("id")).where(
                        cb.equal(interaction.get("workflowRun"), root),
                        cb.equal(interaction.get("status"), RunStatus.ERROR),
                        cb.not(cb.exists(later)));
                predicates.add(cb.exists(failed));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Runs after {@code last} in newest-first order (by start, then id): the export's next batch. */
    static Specification<WorkflowRun> startedBefore(WorkflowRun last) {
        return (root, query, cb) -> cb.or(
                cb.lessThan(root.get("startedAt"), last.getStartedAt()),
                cb.and(cb.equal(root.get("startedAt"), last.getStartedAt()),
                        cb.lessThan(root.get("id"), last.getId())));
    }
}
