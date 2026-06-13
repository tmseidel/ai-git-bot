package org.remus.giteabot.prworkflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD UI for {@link WorkflowConfiguration} rows. Mirrors the structure of
 * the MCP / Bot-tool configuration controllers (list-on-System-settings,
 * dedicated form + sub-page for the workflow selection).
 */
@Slf4j
@Controller
@RequestMapping("/system-settings/workflow-configurations")
@RequiredArgsConstructor
public class WorkflowConfigurationController {

    private final WorkflowConfigurationService configurationService;
    private final WorkflowSelectionService selectionService;

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("workflowConfiguration", new WorkflowConfiguration());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/workflow-configurations/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return configurationService.findById(id)
                .map(configuration -> {
                    model.addAttribute("workflowConfiguration", configuration);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/workflow-configurations/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Workflow configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @GetMapping("/{id}/clone")
    public String cloneForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        try {
            WorkflowConfiguration clone = configurationService.cloneConfiguration(id);
            model.addAttribute("workflowConfiguration", clone);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/workflow-configurations/form";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/system-settings";
        }
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("workflowConfiguration") WorkflowConfiguration workflowConfiguration,
                       Model model, RedirectAttributes redirectAttributes) {
        try {
            WorkflowConfiguration saved = configurationService.save(workflowConfiguration);
            redirectAttributes.addFlashAttribute("success",
                    "Workflow configuration saved. Please select which workflows are enabled.");
            return "redirect:/system-settings/workflow-configurations/" + saved.getId() + "/workflows";
        } catch (Exception e) {
            log.error("Failed to save workflow configuration", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            model.addAttribute("workflowConfiguration", workflowConfiguration);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/workflow-configurations/form";
        }
    }

    @GetMapping("/{id}/workflows")
    public String workflowSelection(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return configurationService.findById(id)
                .map(configuration -> {
                    List<WorkflowSelectionRow> rows = selectionService.loadAvailableWorkflows(id);
                    model.addAttribute("workflowConfiguration", configuration);
                    model.addAttribute("workflows", rows);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/workflow-configurations/workflows";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Workflow configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/{id}/workflows/save")
    public String saveWorkflowSelection(@PathVariable Long id,
                                        @RequestParam(name = "selectedWorkflowKeys", required = false)
                                        List<String> selectedWorkflowKeys,
                                        @RequestParam Map<String, String> allParams,
                                        RedirectAttributes redirectAttributes) {
        try {
            Map<String, Map<String, String>> workflowParams =
                    extractWorkflowParams(allParams, selectedWorkflowKeys);
            if (log.isDebugEnabled()) {
                log.debug("saveWorkflowSelection id={} selectedKeys={} rawParamKeys={} workflowParams={}",
                        id, selectedWorkflowKeys,
                        allParams == null ? null : allParams.keySet(), workflowParams);
            }
            selectionService.saveSelection(id, selectedWorkflowKeys, workflowParams);
            redirectAttributes.addFlashAttribute("success", "Workflow selection saved successfully");
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save workflow selection", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save workflow selection: " + e.getMessage());
            return "redirect:/system-settings/workflow-configurations/" + id + "/workflows";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            configurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Workflow configuration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete workflow configuration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
    }

    /**
     * Returns the selected workflows for the Bot Details modal (mirrors the
     * MCP / built-in tool endpoints under {@code /bots/...}).
     */
    @GetMapping("/{id}/selected-workflows")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> selectedWorkflows(@PathVariable Long id) {
        if (configurationService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> rows = selectionService.describeSelections(id).stream()
                .map(row -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("workflowKey", row.workflowKey());
                    out.put("displayName", row.displayName());
                    out.put("category", row.category());
                    out.put("params", selectionService.maskSecrets(row.workflowKey(), row.persistedParams()));
                    return out;
                })
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * Extracts the {@code params.<workflowKey>.<fieldName>} request
     * parameters submitted by the workflow-selection form into a
     * {@code workflowKey -> {fieldName -> value}} map — the
     * {@link WorkflowSelectionService} validates each per-workflow map
     * against the workflow's
     * {@link org.remus.giteabot.prworkflow.WorkflowParamsSchema} and
     * persists the entries as {@code workflow_selection_params} rows.
     *
     * <p>The dot separator is used (rather than the historic
     * {@code __} double-underscore) because Thymeleaf's expression
     * preprocessing eats any {@code __...__} pair from attribute values,
     * which silently mangled the field names so the controller never
     * received them.</p>
     */
    private Map<String, Map<String, String>> extractWorkflowParams(Map<String, String> allParams,
                                                                   List<String> selectedKeys) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        if (allParams != null) {
            for (Map.Entry<String, String> entry : allParams.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("params.")) {
                    continue;
                }
                String rest = key.substring("params.".length());
                int sep = rest.indexOf('.');
                if (sep < 0) {
                    continue;
                }
                String workflowKey = rest.substring(0, sep);
                String fieldName = rest.substring(sep + 1);
                grouped.computeIfAbsent(workflowKey, k -> new LinkedHashMap<>())
                        .put(fieldName, entry.getValue());
            }
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            if (selectedKeys != null && !selectedKeys.contains(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}

