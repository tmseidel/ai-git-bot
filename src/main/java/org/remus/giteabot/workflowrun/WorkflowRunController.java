package org.remus.giteabot.workflowrun;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.aiusage.AiUsageRawPayload;
import org.remus.giteabot.aiusage.AiUsageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * The Workflow runs page: one entry per triggered workflow with its AI interactions (rounds) and
 * tool calls, filterable through the query string ({@link WorkflowRunFilterForm}), plus the
 * interaction details modal's content (an HTML fragment), the raw payloads, the export, and
 * clearing.
 */
@Controller
@RequiredArgsConstructor
public class WorkflowRunController {

    private final WorkflowRunQueryService queryService;
    private final AiUsageService aiUsageService;
    private final MessageSource messageSource;

    /** The run list, filtered, sorted and paged by the query string ({@link WorkflowRunFilterForm}). */
    @GetMapping("/workflow-runs")
    public String list(@ModelAttribute("filter") WorkflowRunFilterForm filter, Model model) {
        WorkflowRunFilter query = filter.toFilter();
        model.addAttribute("runs", queryService.findRuns(query, filter.getPage() - 1, filter.getSize(),
                filter.isAscending()));
        Map<RunStatus, Long> stateCounts = queryService.countByState(query);
        model.addAttribute("stateCounts", stateCounts);
        model.addAttribute("allCount", stateCounts.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("options", queryService.filterOptions());
        model.addAttribute("rawPayloadsEnabled", queryService.rawPayloadsEnabled());
        model.addAttribute("now", Instant.now());
        model.addAttribute("activeNav", "workflowRuns");
        return "workflow-runs/list";
    }

    /** The details of one interaction: the content of the details modal, rendered as an HTML fragment. */
    @GetMapping("/workflow-runs/interactions/{id}")
    public String interaction(@PathVariable long id, Model model) {
        InteractionDetails details = queryService.interactionDetails(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("details", details);
        model.addAttribute("rawPayloadsEnabled", queryService.rawPayloadsEnabled());
        return "workflow-runs/interaction :: details";
    }

    /**
     * The raw payloads of one interaction, loaded when the raw modal opens. 404 when the
     * interaction is missing or raw payload capture is disabled.
     */
    @GetMapping(value = "/workflow-runs/interactions/{id}/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AiUsageRawPayload> rawPayload(@PathVariable long id) {
        return ResponseEntity.of(queryService.rawPayload(id));
    }

    /** The runs matching the page's filters, with interactions and tool calls, as a JSON download. */
    @GetMapping("/workflow-runs/export")
    public void export(@ModelAttribute WorkflowRunFilterForm filter, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"workflow-runs.json\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        queryService.export(filter.toFilter(), response.getOutputStream());
    }

    /** Removes every finished run with its interactions and tool calls. */
    @PostMapping("/workflow-runs/clear")
    public String clear(RedirectAttributes redirectAttributes) {
        aiUsageService.clearUsage();
        redirectAttributes.addFlashAttribute("success",
                messageSource.getMessage("workflowRuns.cleared", null, LocaleContextHolder.getLocale()));
        return "redirect:/workflow-runs";
    }
}
