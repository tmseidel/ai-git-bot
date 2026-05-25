package org.remus.giteabot.systemsettings;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
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

import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/system-settings")
public class SystemSettingsController {

    private final SystemPromptService systemPromptService;
    private final McpConfigurationService mcpConfigurationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolConfigurationService botToolConfigurationService;
    private final BotToolSelectionService botToolSelectionService;
    private final WorkflowConfigurationService workflowConfigurationService;
    private final DeploymentTargetService deploymentTargetService;

    public SystemSettingsController(SystemPromptService systemPromptService,
                                    McpConfigurationService mcpConfigurationService,
                                    McpToolSelectionService mcpToolSelectionService,
                                    BotToolConfigurationService botToolConfigurationService,
                                    BotToolSelectionService botToolSelectionService,
                                    WorkflowConfigurationService workflowConfigurationService,
                                    DeploymentTargetService deploymentTargetService) {
        this.systemPromptService = systemPromptService;
        this.mcpConfigurationService = mcpConfigurationService;
        this.mcpToolSelectionService = mcpToolSelectionService;
        this.botToolConfigurationService = botToolConfigurationService;
        this.botToolSelectionService = botToolSelectionService;
        this.workflowConfigurationService = workflowConfigurationService;
        this.deploymentTargetService = deploymentTargetService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("systemPrompts", systemPromptService.findAll());
        model.addAttribute("mcpConfigurations", mcpConfigurationService.findAll());
        model.addAttribute("botToolConfigurations", botToolConfigurationService.findAll());
        model.addAttribute("workflowConfigurations", workflowConfigurationService.findAll());
        model.addAttribute("deploymentTargets", deploymentTargetService.findAll());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/list";
    }

    @GetMapping("/mcp-configurations/new")
    public String newMcpForm(Model model) {
        model.addAttribute("mcpConfiguration", new McpConfiguration());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/mcp-form";
    }

    @GetMapping("/mcp-configurations/{id}/edit")
    public String editMcpForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return mcpConfigurationService.findById(id)
                .map(mcpConfiguration -> {
                    model.addAttribute("mcpConfiguration", mcpConfiguration);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/mcp-form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "MCP configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/mcp-configurations/save")
    public String saveMcp(@ModelAttribute McpConfiguration mcpConfiguration, Model model,
                          RedirectAttributes redirectAttributes) {
        try {
            McpConfiguration saved = mcpConfigurationService.save(mcpConfiguration);
            redirectAttributes.addFlashAttribute("success",
                    "MCP configuration saved. Please select which MCP tools are allowed in prompts.");
            return "redirect:/system-settings/mcp-configurations/" + saved.getId() + "/tools";
        } catch (Exception e) {
            log.error("Failed to save MCP configuration", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            model.addAttribute("mcpConfiguration", mcpConfiguration);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/mcp-form";
        }
    }

    @GetMapping("/mcp-configurations/{id}/tools")
    public String mcpToolSelection(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return mcpConfigurationService.findById(id)
                .map(configuration -> {
                    List<McpToolSelectionRow> tools = mcpToolSelectionService.loadAvailableTools(id);
                    model.addAttribute("mcpConfiguration", configuration);
                    model.addAttribute("tools", tools);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/mcp-tool-selection";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "MCP configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/mcp-configurations/{id}/tools/save")
    public String saveMcpToolSelection(@PathVariable Long id,
                                       @RequestParam(name = "selectedQualifiedNames", required = false)
                                       List<String> selectedQualifiedNames,
                                       RedirectAttributes redirectAttributes) {
        try {
            mcpToolSelectionService.saveSelection(id, selectedQualifiedNames);
            redirectAttributes.addFlashAttribute("success", "MCP tool selection saved successfully");
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save MCP tool selection", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save MCP tool selection: " + e.getMessage());
            return "redirect:/system-settings/mcp-configurations/" + id + "/tools";
        }
    }

    @PostMapping("/mcp-configurations/{id}/delete")
    public String deleteMcp(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            mcpConfigurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "MCP configuration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete MCP configuration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
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
                    clone.setWriterAgentSystemPrompt(source.getWriterAgentSystemPrompt());
                    clone.setE2ePlannerSystemPrompt(source.getE2ePlannerSystemPrompt());
                    clone.setE2eAuthorSystemPrompt(source.getE2eAuthorSystemPrompt());
                    clone.setE2eRunnerSystemPrompt(source.getE2eRunnerSystemPrompt());
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
    public ResponseEntity<Map<String, String>> preview(@PathVariable Long id) {
        return systemPromptService.findById(id)
                .map(systemPrompt -> ResponseEntity.ok(Map.of(
                        "name", systemPrompt.getName(),
                        "reviewSystemPrompt", systemPrompt.getReviewSystemPrompt(),
                        "issueAgentSystemPrompt", systemPrompt.getIssueAgentSystemPrompt(),
                        "writerAgentSystemPrompt", systemPrompt.getWriterAgentSystemPrompt(),
                        "e2ePlannerSystemPrompt", systemPrompt.getE2ePlannerSystemPrompt(),
                        "e2eAuthorSystemPrompt", systemPrompt.getE2eAuthorSystemPrompt(),
                        "e2eRunnerSystemPrompt", systemPrompt.getE2eRunnerSystemPrompt())))
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    // ------------------------------------------------------------------
    // Bot Tool Configurations (built-in agent tools whitelist per bot)
    // ------------------------------------------------------------------

    @GetMapping("/bot-tools/new")
    public String newBotToolForm(Model model) {
        model.addAttribute("botToolConfiguration", new BotToolConfiguration());
        model.addAttribute("activeNav", "system-settings");
        return "system-settings/bot-tools-form";
    }

    @GetMapping("/bot-tools/{id}/edit")
    public String editBotToolForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return botToolConfigurationService.findById(id)
                .map(configuration -> {
                    model.addAttribute("botToolConfiguration", configuration);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/bot-tools-form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Tool configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @GetMapping("/bot-tools/{id}/clone")
    public String cloneBotToolForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        try {
            BotToolConfiguration clone = botToolConfigurationService.cloneConfiguration(id);
            model.addAttribute("botToolConfiguration", clone);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/bot-tools-form";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/system-settings";
        }
    }

    @PostMapping("/bot-tools/save")
    public String saveBotTool(@ModelAttribute("botToolConfiguration") BotToolConfiguration botToolConfiguration,
                              Model model, RedirectAttributes redirectAttributes) {
        try {
            BotToolConfiguration saved = botToolConfigurationService.save(botToolConfiguration);
            redirectAttributes.addFlashAttribute("success",
                    "Tool configuration saved. Please select which built-in tools are allowed.");
            return "redirect:/system-settings/bot-tools/" + saved.getId() + "/tools";
        } catch (Exception e) {
            log.error("Failed to save tool configuration", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            model.addAttribute("botToolConfiguration", botToolConfiguration);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/bot-tools-form";
        }
    }

    @GetMapping("/bot-tools/{id}/tools")
    public String botToolSelection(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return botToolConfigurationService.findById(id)
                .map(configuration -> {
                    List<BotToolSelectionRow> tools = botToolSelectionService.loadAvailableTools(id);
                    model.addAttribute("botToolConfiguration", configuration);
                    model.addAttribute("tools", tools);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/bot-tools-selection";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Tool configuration not found");
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/bot-tools/{id}/tools/save")
    public String saveBotToolSelection(@PathVariable Long id,
                                       @RequestParam(name = "selectedToolNames", required = false)
                                       List<String> selectedToolNames,
                                       RedirectAttributes redirectAttributes) {
        try {
            botToolSelectionService.saveSelection(id, selectedToolNames);
            redirectAttributes.addFlashAttribute("success", "Tool selection saved successfully");
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save tool selection", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save tool selection: " + e.getMessage());
            return "redirect:/system-settings/bot-tools/" + id + "/tools";
        }
    }

    @PostMapping("/bot-tools/{id}/delete")
    public String deleteBotTool(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            botToolConfigurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Tool configuration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete tool configuration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
    }
}
