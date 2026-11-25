package org.remus.giteabot.workflowrun;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.aiusage.AiInteractionRepository;
import org.remus.giteabot.aiusage.AiToolCall;
import org.remus.giteabot.aiusage.AiToolCallRepository;
import org.remus.giteabot.aiusage.AiUsageRawPayload;
import org.remus.giteabot.aiusage.InteractionRow;
import org.remus.giteabot.aiusage.ToolTotals;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SequenceWriter;

import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The read side of the Workflow runs page. Only facts are stored, so every total (rounds,
 * tokens, tool calls, model and tool time, output truncated) is computed here from the
 * interactions and tool calls of the runs on the page.
 */
@Service
@RequiredArgsConstructor
public class WorkflowRunQueryService {

    /** Default page size of the run list. */
    public static final int PAGE_SIZE = 20;

    /** Upper bound for requested page sizes, the largest the rows-per-page select offers. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Runs per batch when exporting — keeps heap bounded however many runs match. */
    private static final int EXPORT_BATCH_SIZE = 50;

    private final WorkflowRunRepository runRepository;
    private final AiInteractionRepository interactionRepository;
    private final AiToolCallRepository toolCallRepository;
    private final AiUsageProperties usageProperties;
    private final TriggerLinks triggerLinks;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /** A page of runs matching {@code filter}, sorted by start, each with its attempts and totals. */
    @Transactional(readOnly = true)
    public Page<RunView> findRuns(WorkflowRunFilter filter, int page, int pageSize, boolean ascending) {
        Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<WorkflowRun> runs = runRepository.findAll(WorkflowRunSpecifications.matching(filter),
                PageRequest.of(Math.max(0, page), effectivePageSize(pageSize),
                        Sort.by(direction, "startedAt").and(Sort.by(direction, "id"))));
        List<Long> runIds = runs.getContent().stream().map(WorkflowRun::getId).toList();
        if (runIds.isEmpty()) {
            return new PageImpl<>(List.of(), runs.getPageable(), runs.getTotalElements());
        }
        Map<Long, List<InteractionRow>> rowsByRun = rowsByRun(runIds);
        Map<Long, ToolTotals> toolTotals = toolCallRepository.totalsByInteraction(runIds).stream()
                .collect(Collectors.toMap(ToolTotals::interactionId, Function.identity()));
        Map<Long, String> links = triggerLinks.urlsOf(runs.getContent());
        Instant now = Instant.now();
        return runs.map(run -> {
            List<InteractionRow> rows = rowsByRun.getOrDefault(run.getId(), List.of());
            List<AttemptView> attempts = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                InteractionRow row = rows.get(i);
                attempts.add(new AttemptView(row,
                        toolTotals.getOrDefault(row.id(), ToolTotals.none(row.id())),
                        i == rows.size() - 1 && run.isRoundLimitReached()));
            }
            return RunView.of(run, attempts, links.get(run.getId()), now);
        });
    }

    /** How many runs match {@code filter} in each state, ignoring the filter's own state. */
    @Transactional(readOnly = true)
    public Map<RunStatus, Long> countByState(WorkflowRunFilter filter) {
        Map<RunStatus, Long> counts = new EnumMap<>(RunStatus.class);
        for (RunStatus state : RunStatus.values()) {
            counts.put(state, runRepository.count(WorkflowRunSpecifications.matching(filter.withState(state))));
        }
        return counts;
    }

    /** The values the dropdown filters offer: only those that have runs. */
    @Transactional(readOnly = true)
    public FilterOptions filterOptions() {
        return new FilterOptions(runRepository.findProjects(), runRepository.findWorkflowTypes(),
                runRepository.findBots());
    }

    /** Everything the details modal shows about one interaction, or empty when it does not exist. */
    @Transactional(readOnly = true)
    public Optional<InteractionDetails> interactionDetails(long id) {
        Optional<InteractionRow> found = interactionRepository.findRowById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        InteractionRow row = found.get();
        WorkflowRun run = runRepository.findById(row.runId()).orElseThrow();
        return Optional.of(new InteractionDetails(row, interactionRepository.findStackTraceById(id), run,
                interactionRepository.countRoundsOfRun(run.getId()), triggerLinks.urlsOf(List.of(run)).get(run.getId()),
                toolCallRepository.findByInteraction_IdOrderBySequence(id)));
    }

    /**
     * The raw payloads of one interaction, or empty when it does not exist or raw payload capture
     * is disabled (stored payloads are then not shown either).
     */
    @Transactional(readOnly = true)
    public Optional<AiUsageRawPayload> rawPayload(long id) {
        return rawPayloadsEnabled() ? interactionRepository.findRawPayloadById(id) : Optional.empty();
    }

    public boolean rawPayloadsEnabled() {
        return usageProperties.isRawPayloadsEnabled();
    }

    /**
     * Streams the runs matching {@code filter} as a JSON array of {@link RunExport}s, newest first.
     * Runs are read in batches of {@value #EXPORT_BATCH_SIZE}, each continuing after the last run
     * written (by start, then id) rather than at an offset, so runs started or cleared during the
     * export neither repeat nor push others out. The persistence context is cleared after each
     * batch, so heap use stays bounded however many runs match.
     */
    public void export(WorkflowRunFilter filter, OutputStream outputStream) {
        export(filter, outputStream, EXPORT_BATCH_SIZE);
    }

    void export(WorkflowRunFilter filter, OutputStream outputStream, int batchSize) {
        Specification<WorkflowRun> matching = WorkflowRunSpecifications.matching(filter);
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "startedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        try (SequenceWriter writer = objectMapper.writer().writeValuesAsArray(outputStream)) {
            WorkflowRun last = null;
            List<WorkflowRun> batch;
            do {
                Specification<WorkflowRun> next = last == null
                        ? matching : matching.and(WorkflowRunSpecifications.startedBefore(last));
                batch = runRepository.findBy(next, query -> query.sortBy(newestFirst).limit(batchSize).all());
                if (!batch.isEmpty()) {
                    List<Long> runIds = batch.stream().map(WorkflowRun::getId).toList();
                    Map<Long, List<InteractionRow>> rowsByRun = rowsByRun(runIds);
                    Map<Long, List<AiToolCall>> callsByInteraction = toolCallRepository.findByRunIds(runIds).stream()
                            .collect(Collectors.groupingBy(call -> call.getInteraction().getId()));
                    for (WorkflowRun run : batch) {
                        writer.write(new RunExport(run, rowsByRun.getOrDefault(run.getId(), List.of()).stream()
                                .map(row -> new RunExport.Interaction(row,
                                        callsByInteraction.getOrDefault(row.id(), List.of())))
                                .toList()));
                    }
                    last = batch.getLast();
                }
                writer.flush();
                entityManager.clear();
            } while (batch.size() == batchSize);
        }
    }

    /** The interactions of the given runs (without raw payloads), by run, in round and attempt order. */
    private Map<Long, List<InteractionRow>> rowsByRun(Collection<Long> runIds) {
        return interactionRepository.findRowsByRunIds(runIds).stream()
                .collect(Collectors.groupingBy(InteractionRow::runId, LinkedHashMap::new, Collectors.toList()));
    }

    private static int effectivePageSize(int pageSize) {
        return pageSize > 0 ? Math.min(pageSize, MAX_PAGE_SIZE) : PAGE_SIZE;
    }
}
