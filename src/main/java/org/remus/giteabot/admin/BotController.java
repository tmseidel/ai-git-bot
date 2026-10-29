package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptService;
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
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@Controller
@RequestMapping("/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotService botService;
    private final AiIntegrationService aiIntegrationService;
    private final GitIntegrationService gitIntegrationService;
    private final SystemPromptService systemPromptService;
    private final McpConfigurationService mcpConfigurationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolConfigurationService botToolConfigurationService;
    private final BotToolSelectionService botToolSelectionService;
    private final WorkflowConfigurationService workflowConfigurationService;
    private final DeploymentTargetService deploymentTargetService;
    private final MessageSource messageSource;
    private final GitUserService gitUserService;

    @GetMapping
    public String list(Model model) {
        List<Bot> bots = botService.findAll();
        model.addAttribute("bots", bots);
        model.addAttribute("gitUsernames",
                gitUserService.findCachedUsernames(bots.stream().map(Bot::getGitIntegration).toList()));
        model.addAttribute("activeNav", "bots");
        return "bots/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        Bot bot = new Bot();
        systemPromptService.findDefault().ifPresent(bot::setSystemPrompt);
        botToolConfigurationService.findDefault().ifPresent(bot::setToolConfiguration);
        workflowConfigurationService.findDefault().ifPresent(bot::setWorkflowConfiguration);
        workflowConfigurationService.findDefault(WorkflowConfigurationKind.ISSUE)
                .ifPresent(bot::setIssueWorkflowConfiguration);
        model.addAttribute("bot", bot);
        addFormAttributes(model);
        model.addAttribute("missingAiIntegration", aiIntegrationService.findAll().isEmpty());
        model.addAttribute("missingGitIntegration", gitIntegrationService.findAll().isEmpty());
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
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.botNotFound", null, LocaleContextHolder.getLocale()));
                    return "redirect:/bots";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Bot bot,
                        @RequestParam Long aiIntegrationId,
                        @RequestParam Long gitIntegrationId,
                        @RequestParam Long systemPromptId,
                        @RequestParam(required = false) Long mcpConfigurationId,
                        @RequestParam Long toolConfigurationId,
                        @RequestParam(required = false) Long workflowConfigurationId,
                        @RequestParam(required = false) Long issueWorkflowConfigurationId,
                        @RequestParam(required = false) Long deploymentTargetId,
                        @RequestParam(defaultValue = "false") boolean clearWebhookSigningSecret,
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
            BotToolConfiguration toolConfiguration = botToolConfigurationService.findById(toolConfigurationId)
                    .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
            WorkflowConfiguration workflowConfiguration;
            if (workflowConfigurationId != null) {
                workflowConfiguration = workflowConfigurationService.findById(workflowConfigurationId)
                        .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
            } else {
                workflowConfiguration = workflowConfigurationService.findDefault().orElse(null);
            }
            WorkflowConfiguration issueWorkflowConfiguration;
            if (issueWorkflowConfigurationId != null) {
                issueWorkflowConfiguration = workflowConfigurationService.findById(issueWorkflowConfigurationId)
                        .orElseThrow(() -> new IllegalArgumentException("Issue workflow configuration not found"));
            } else {
                issueWorkflowConfiguration = workflowConfigurationService
                        .findDefault(WorkflowConfigurationKind.ISSUE).orElse(null);
            }

            bot.setAiIntegration(aiIntegration);
            bot.setGitIntegration(gitIntegration);
            bot.setSystemPrompt(systemPrompt);
            bot.setMcpConfiguration(mcpConfiguration);
            bot.setToolConfiguration(toolConfiguration);
            bot.setWorkflowConfiguration(workflowConfiguration);
            bot.setIssueWorkflowConfiguration(issueWorkflowConfiguration);
            DeploymentTarget deploymentTarget = null;
            if (deploymentTargetId != null) {
                deploymentTarget = deploymentTargetService.findById(deploymentTargetId)
                        .orElseThrow(() -> new IllegalArgumentException("Deployment target not found"));
            }
            bot.setDeploymentTarget(deploymentTarget);
            botService.save(bot, clearWebhookSigningSecret);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.botSaved", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to save Bot", e);
            model.addAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
            addFormAttributes(model);
            return "bots/form";
        }
        return "redirect:/bots";
    }

    private void addFormAttributes(Model model) {
        List<SystemPrompt> systemPrompts = systemPromptService.findAll();
        model.addAttribute("aiIntegrations", aiIntegrationService.findAll());
        List<GitIntegration> gitIntegrations = gitIntegrationService.findAll();
        model.addAttribute("gitIntegrations", gitIntegrations);
        model.addAttribute("gitUsernames", gitUserService.findCachedUsernames(gitIntegrations));
        // Defaults so the missing-integration modal expression is null-safe on the
        // edit form and the save-error re-render; newForm() overrides these.
        model.addAttribute("missingAiIntegration", false);
        model.addAttribute("missingGitIntegration", false);
        model.addAttribute("systemPrompts", systemPrompts);
        model.addAttribute("mcpConfigurations", mcpConfigurationService.findAll());
        model.addAttribute("toolConfigurations", botToolConfigurationService.findAll());
        model.addAttribute("workflowConfigurations",
                workflowConfigurationService.findAll(WorkflowConfigurationKind.PR));
        model.addAttribute("issueWorkflowConfigurations",
                workflowConfigurationService.findAll(WorkflowConfigurationKind.ISSUE));
        model.addAttribute("deploymentTargets", deploymentTargetService.findAll());
        model.addAttribute("activeNav", "bots");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            botService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.botDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete Bot", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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

    @GetMapping("/bot-tools/{id}/selected-tools")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> selectedBuiltinTools(@PathVariable Long id) {
        if (botToolConfigurationService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, String>> rows = botToolSelectionService.loadSelectedTools(id).stream()
                .map(tool -> Map.of(
                        "toolName", tool.toolName(),
                        "toolKind", tool.toolKind(),
                        "description", tool.description() == null ? "" : tool.description()))
                .toList();
        return ResponseEntity.ok(rows);
    }
}