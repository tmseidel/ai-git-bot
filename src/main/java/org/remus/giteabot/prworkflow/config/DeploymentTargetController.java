package org.remus.giteabot.prworkflow.config;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * CRUD UI for {@link DeploymentTarget} rows. Mirrors the structure of the
 * {@link WorkflowConfigurationController} (System-settings landing,
 * dedicated list/form sub-pages).
 *
 * <p>The strategy-type dropdown is restricted to the strategies that ship
 * with the current milestone via
 * {@link DeploymentTargetService#availableStrategyTypes()} —
 * {@code WEBHOOK}, {@code STATIC} (M3) and {@code MCP} (M5). The remaining
 * {@code CI_ACTION} value will appear automatically once M6 wires its
 * strategy bean.</p>
 */
@Slf4j
@Controller
@RequestMapping("/system-settings/deployment-targets")
public class DeploymentTargetController {

    private static final String VIEW_FORM = "system-settings/deployment-targets/form";

    private final DeploymentTargetService service;
    private final String publicBaseUrl;

    public DeploymentTargetController(DeploymentTargetService service,
                                      @Value("${ai-git-bot.public-base-url:}") String publicBaseUrl) {
        this.service = service;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    @GetMapping
    public String list() {
        // The deployment-targets list is rendered as a card on the System
        // settings landing page; redirect to keep navigation consistent.
        return "redirect:/system-settings";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        DeploymentTarget blank = new DeploymentTarget();
        blank.setStrategyType(DeploymentStrategyType.WEBHOOK);
        blank.setConfigJson("{}");
        blank.setTimeoutSeconds(600);
        populateForm(model, blank);
        return VIEW_FORM;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return service.findById(id)
                .map(target -> {
                    populateForm(model, target);
                    return VIEW_FORM;
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Deployment target not found");
                    return "redirect:/system-settings/deployment-targets";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("deploymentTarget") DeploymentTarget target,
                       Model model, RedirectAttributes redirectAttributes) {
        try {
            DeploymentTarget saved = service.save(target);
            redirectAttributes.addFlashAttribute("success",
                    "Deployment target '" + saved.getName() + "' saved successfully");
            return "redirect:/system-settings/deployment-targets";
        } catch (Exception e) {
            log.error("Failed to save deployment target", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            populateForm(model, target);
            return VIEW_FORM;
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            service.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Deployment target deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete deployment target", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings/deployment-targets";
    }

    private void populateForm(Model model, DeploymentTarget target) {
        model.addAttribute("deploymentTarget", target);
        model.addAttribute("strategyTypes", service.availableStrategyTypes());
        model.addAttribute("callbackUrlPattern", callbackUrlPattern());
        model.addAttribute("activeNav", "system-settings");
    }

    private String callbackUrlPattern() {
        String base = publicBaseUrl.isBlank() ? "{publicBaseUrl}" : publicBaseUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/workflow-callback/{runId}/{secret}";
    }

}


