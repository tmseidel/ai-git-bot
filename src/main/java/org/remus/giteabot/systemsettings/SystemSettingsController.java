package org.remus.giteabot.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/system-settings")
public class SystemSettingsController {

    private final SystemPromptService systemPromptService;

    public SystemSettingsController(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("systemPrompts", systemPromptService.findAll());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/list";
    }

    @GetMapping("/system-prompts/new")
    public String newForm(Model model) {
        model.addAttribute("systemPrompt", new SystemPrompt());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/form";
    }

    @GetMapping("/system-prompts/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return systemPromptService.findById(id)
                .map(systemPrompt -> {
                    model.addAttribute("systemPrompt", systemPrompt);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "System prompt not found");
                    return "redirect:/system-settings";
                });
    }

    @GetMapping("/system-prompts/{id}/clone")
    public String cloneForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return systemPromptService.findById(id)
                .map(source -> {
                    SystemPrompt clone = new SystemPrompt();
                    clone.setName("Copy of " + source.getName());
                    clone.setReviewSystemPrompt(source.getReviewSystemPrompt());
                    clone.setIssueAgentSystemPrompt(source.getIssueAgentSystemPrompt());
                    model.addAttribute("systemPrompt", clone);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "System prompt not found");
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/system-prompts/save")
    public String save(@ModelAttribute SystemPrompt systemPrompt, Model model, RedirectAttributes redirectAttributes) {
        try {
            if (systemPrompt.getId() != null) {
                SystemPrompt existing = systemPromptService.findById(systemPrompt.getId())
                        .orElseThrow(() -> new IllegalArgumentException("System prompt not found"));
                systemPrompt.setDefaultEntry(existing.isDefaultEntry());
            }
            systemPromptService.save(systemPrompt);
            redirectAttributes.addFlashAttribute("success", "System prompt saved successfully");
        } catch (Exception e) {
            log.error("Failed to save system prompt", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            model.addAttribute("systemPrompt", systemPrompt);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/form";
        }
        return "redirect:/system-settings";
    }

    @GetMapping("/system-prompts/{id}/preview")
    @ResponseBody
    public Map<String, String> preview(@PathVariable Long id) {
        SystemPrompt systemPrompt = systemPromptService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("System prompt not found"));
        return Map.of(
                "name", systemPrompt.getName(),
                "reviewSystemPrompt", systemPrompt.getReviewSystemPrompt(),
                "issueAgentSystemPrompt", systemPrompt.getIssueAgentSystemPrompt());
    }

    @PostMapping("/system-prompts/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            systemPromptService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "System prompt deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete system prompt", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
    }
}
