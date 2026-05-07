package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/bots")
public class BotController {

    private final BotService botService;
    private final AiIntegrationService aiIntegrationService;
    private final GitIntegrationService gitIntegrationService;
    private final SystemPromptService systemPromptService;
    private final McpConfigurationService mcpConfigurationService;
    private final McpToolSelectionService mcpToolSelectionService;

    public BotController(BotService botService,
                         AiIntegrationService aiIntegrationService,
                         GitIntegrationService gitIntegrationService,
                         SystemPromptService systemPromptService,
                         McpConfigurationService mcpConfigurationService,
                         McpToolSelectionService mcpToolSelectionService) {
        this.botService = botService;
        this.aiIntegrationService = aiIntegrationService;
        this.gitIntegrationService = gitIntegrationService;
        this.systemPromptService = systemPromptService;
        this.mcpConfigurationService = mcpConfigurationService;
        this.mcpToolSelectionService = mcpToolSelectionService;
    }

    @GetMapping
    public String list(Model model) {
        List<Bot> bots = botService.findAll();
        model.addAttribute("bots", bots);
        model.addAttribute("activeNav", "bots");
        return "bots/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        Bot bot = new Bot();
        systemPromptService.findDefault().ifPresent(bot::setSystemPrompt);
        model.addAttribute("bot", bot);
        addFormAttributes(model);
        return "bots/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return botService.findById(id)
                .map(bot -> {
                    model.addAttribute("bot", bot);
                    addFormAttributes(model);
                    return "bots/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Bot not found");
                    return "redirect:/bots";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Bot bot,
                        @RequestParam Long aiIntegrationId,
                        @RequestParam Long gitIntegrationId,
                        @RequestParam Long systemPromptId,
                        @RequestParam(required = false) Long mcpConfigurationId,
                        Model model,
                        RedirectAttributes redirectAttributes) {
        try {
            AiIntegration aiIntegration = aiIntegrationService.findById(aiIntegrationId)
                    .orElseThrow(() -> new IllegalArgumentException("AI Integration not found"));
            GitIntegration gitIntegration = gitIntegrationService.findById(gitIntegrationId)
                    .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
            SystemPrompt systemPrompt = systemPromptService.findById(systemPromptId)
                    .orElseThrow(() -> new IllegalArgumentException("System prompt not found"));
            McpConfiguration mcpConfiguration = null;
            if (mcpConfigurationId != null) {
                mcpConfiguration = mcpConfigurationService.findById(mcpConfigurationId)
                        .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
            }

            bot.setAiIntegration(aiIntegration);
            bot.setGitIntegration(gitIntegration);
            bot.setSystemPrompt(systemPrompt);
            bot.setMcpConfiguration(mcpConfiguration);
            botService.save(bot);
            redirectAttributes.addFlashAttribute("success", "Bot saved successfully");
        } catch (Exception e) {
            log.error("Failed to save Bot", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            addFormAttributes(model);
            return "bots/form";
        }
        return "redirect:/bots";
    }

    private void addFormAttributes(Model model) {
        List<SystemPrompt> systemPrompts = systemPromptService.findAll();
        model.addAttribute("aiIntegrations", aiIntegrationService.findAll());
        model.addAttribute("gitIntegrations", gitIntegrationService.findAll());
        model.addAttribute("systemPrompts", systemPrompts);
        model.addAttribute("mcpConfigurations", mcpConfigurationService.findAll());
        model.addAttribute("botTypes", BotType.values());
        model.addAttribute("activeNav", "bots");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            botService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Bot deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete Bot", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/bots";
    }

    @GetMapping("/mcp-configurations/{id}/selected-tools")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> selectedMcpTools(@PathVariable Long id) {
        if (mcpConfigurationService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, String>> rows = mcpToolSelectionService.loadSelectedTools(id).stream()
                .map(tool -> Map.of(
                        "qualifiedName", tool.qualifiedName(),
                        "serverName", tool.serverName(),
                        "toolName", tool.toolName(),
                        "title", tool.title() == null ? "" : tool.title(),
                        "description", tool.description() == null ? "" : tool.description()))
                .toList();
        return ResponseEntity.ok(rows);
    }
}
