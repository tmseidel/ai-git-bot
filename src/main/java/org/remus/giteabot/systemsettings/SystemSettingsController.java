package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpSession;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
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
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@Controller
@RequestMapping("/system-settings")
@RequiredArgsConstructor
public class SystemSettingsController {

    private final SystemPromptService systemPromptService;
    private final McpConfigurationService mcpConfigurationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolConfigurationService botToolConfigurationService;
    private final BotToolSelectionService botToolSelectionService;
    private final WorkflowConfigurationService workflowConfigurationService;
    private final DeploymentTargetService deploymentTargetService;
    private final MessageSource messageSource;

    private record McpApprovalForm(String token, Map<String, McpToolSelectionService.ReviewReadOnlyApproval> evidence) {
    }

    private static String approvalFormKey(Long id) {
        return "mcpReviewApprovalForm:" + id;
    }

    private static void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("MCP review approval requires ADMIN");
        }
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("systemPrompts", systemPromptService.findAll());
        model.addAttribute("mcpConfigurations", mcpConfigurationService.findAll());
        model.addAttribute("botToolConfigurations", botToolConfigurationService.findAll());
        model.addAttribute("workflowConfigurations",
                workflowConfigurationService.findAll(WorkflowConfigurationKind.PR));
        model.addAttribute("issueWorkflowConfigurations",
                workflowConfigurationService.findAll(WorkflowConfigurationKind.ISSUE));
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
                    // The stored JSON is encrypted - render the decrypted view in the form.
                    model.addAttribute("mcpConfiguration", mcpConfigurationService.decryptedView(mcpConfiguration));
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/mcp-form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.mcpNotFound", null, LocaleContextHolder.getLocale()));
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/mcp-configurations/save")
    public String saveMcp(@ModelAttribute McpConfiguration mcpConfiguration, Model model,
                          RedirectAttributes redirectAttributes) {
        try {
            McpConfiguration saved = mcpConfigurationService.save(mcpConfiguration);
            redirectAttributes.addFlashAttribute("success",
                    messageSource.getMessage("flash.mcpSaved", null, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings/mcp-configurations/" + saved.getId() + "/tools";
        } catch (Exception e) {
            log.error("Failed to save MCP configuration", e);
            model.addAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
            model.addAttribute("mcpConfiguration", mcpConfiguration);
            model.addAttribute("activeNav", "system-settings");
            return "system-settings/mcp-form";
        }
    }

    @GetMapping("/mcp-configurations/{id}/tools")
    public String mcpToolSelection(@PathVariable Long id, Model model, HttpSession session,
                                   Authentication authentication, RedirectAttributes redirectAttributes) {
        requireAdmin(authentication);
        return mcpConfigurationService.findById(id)
                .map(configuration -> {
                    McpToolSelectionService.SelectionSnapshot snapshot = mcpToolSelectionService.loadSelectionSnapshot(id);
                    model.addAttribute("mcpConfiguration", configuration);
                    model.addAttribute("tools", snapshot.tools());
                    McpApprovalForm form = new McpApprovalForm(UUID.randomUUID().toString(),
                            snapshot.approvalCandidates());
                    session.setAttribute(approvalFormKey(id), form);
                    model.addAttribute("approvalFormToken", form.token());
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/mcp-tool-selection";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.mcpNotFound", null, LocaleContextHolder.getLocale()));
                    return "redirect:/system-settings";
                });
    }

    @PostMapping("/mcp-configurations/{id}/tools/save")
    public String saveMcpToolSelection(@PathVariable Long id,
                                       @RequestParam(name = "selectedQualifiedNames", required = false)
                                       List<String> selectedQualifiedNames,
                                       @RequestParam(name = "reviewReadOnlyApprovedQualifiedNames", required = false)
                                       List<String> approvedQualifiedNames,
                                       @RequestParam(name = "reviewReadOnlyRevokedQualifiedNames", required = false)
                                       List<String> revokedQualifiedNames,
                                       @RequestParam(name = "approvalFormToken", required = false) String approvalFormToken,
                                       HttpSession session, Authentication authentication,
                                       RedirectAttributes redirectAttributes) {
        requireAdmin(authentication);
        try {
            if (approvalFormToken == null && (approvedQualifiedNames == null || approvedQualifiedNames.isEmpty())
                    && (revokedQualifiedNames == null || revokedQualifiedNames.isEmpty())) {
                mcpToolSelectionService.saveSelection(id, selectedQualifiedNames);
            } else {
                Object savedForm = session.getAttribute(approvalFormKey(id));
                if (!(savedForm instanceof McpApprovalForm form) || !form.token().equals(approvalFormToken)) {
                    throw new IllegalArgumentException("Reload the MCP tool form before changing read-only approvals");
                }
                session.removeAttribute(approvalFormKey(id));
                mcpToolSelectionService.saveSelection(id, selectedQualifiedNames, approvedQualifiedNames,
                        revokedQualifiedNames, form.evidence());
            }
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.mcpToolSelectionSaved", null, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save MCP tool selection", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings/mcp-configurations/" + id + "/tools";
        }
    }

    @PostMapping("/mcp-configurations/{id}/delete")
    public String deleteMcp(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            mcpConfigurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.mcpDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete MCP configuration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.systemPromptNotFound", null, LocaleContextHolder.getLocale()));
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
                    clone.setReviewAgentSystemPrompt(source.getReviewAgentSystemPrompt());
                    clone.setIssueAgentSystemPrompt(source.getIssueAgentSystemPrompt());
                    clone.setWriterAgentSystemPrompt(source.getWriterAgentSystemPrompt());
                    clone.setE2ePlannerSystemPrompt(source.getE2ePlannerSystemPrompt());
                    clone.setE2eAuthorSystemPrompt(source.getE2eAuthorSystemPrompt());
                    clone.setE2eRunnerSystemPrompt(source.getE2eRunnerSystemPrompt());
                    clone.setUnitTestAuthorSystemPrompt(source.getUnitTestAuthorSystemPrompt());
                    clone.setReadmeSyncSystemPrompt(source.getReadmeSyncSystemPrompt());
                    clone.setI18nCoverageSystemPrompt(source.getI18nCoverageSystemPrompt());
                    model.addAttribute("systemPrompt", clone);
                    model.addAttribute("activeNav", "system-settings");
                    return "system-settings/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.systemPromptNotFound", null, LocaleContextHolder.getLocale()));
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
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.systemPromptSaved", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to save system prompt", e);
            model.addAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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
                .map(systemPrompt -> ResponseEntity.ok(Map.ofEntries(
                        Map.entry("name", systemPrompt.getName()),
                        Map.entry("reviewSystemPrompt", systemPrompt.getReviewSystemPrompt()),
                        Map.entry("reviewAgentSystemPrompt", systemPrompt.getReviewAgentSystemPrompt()),
                        Map.entry("issueAgentSystemPrompt", systemPrompt.getIssueAgentSystemPrompt()),
                        Map.entry("writerAgentSystemPrompt", systemPrompt.getWriterAgentSystemPrompt()),
                        Map.entry("e2ePlannerSystemPrompt", systemPrompt.getE2ePlannerSystemPrompt()),
                        Map.entry("e2eAuthorSystemPrompt", systemPrompt.getE2eAuthorSystemPrompt()),
                        Map.entry("e2eRunnerSystemPrompt", systemPrompt.getE2eRunnerSystemPrompt()),
                        Map.entry("unitTestAuthorSystemPrompt", systemPrompt.getUnitTestAuthorSystemPrompt()),
                        Map.entry("readmeSyncSystemPrompt", systemPrompt.getReadmeSyncSystemPrompt()),
                        Map.entry("i18nCoverageSystemPrompt", systemPrompt.getI18nCoverageSystemPrompt()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/system-prompts/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            systemPromptService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.systemPromptDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete system prompt", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.toolConfigNotFound", null, LocaleContextHolder.getLocale()));
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
                    messageSource.getMessage("flash.toolConfigSaved", null, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings/bot-tools/" + saved.getId() + "/tools";
        } catch (Exception e) {
            log.error("Failed to save tool configuration", e);
            model.addAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.toolConfigNotFound", null, LocaleContextHolder.getLocale()));
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
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.toolSelectionSaved", null, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save tool selection", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
            return "redirect:/system-settings/bot-tools/" + id + "/tools";
        }
    }

    @PostMapping("/bot-tools/{id}/delete")
    public String deleteBotTool(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            botToolConfigurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.toolConfigDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete tool configuration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/system-settings";
    }
}
