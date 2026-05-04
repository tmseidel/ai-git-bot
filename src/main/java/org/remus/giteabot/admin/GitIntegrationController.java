package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/git-integrations")
public class GitIntegrationController {

    private final GitIntegrationService gitIntegrationService;

    public GitIntegrationController(GitIntegrationService gitIntegrationService) {
        this.gitIntegrationService = gitIntegrationService;
    }

    @GetMapping
    public String list(Model model) {
        List<GitIntegration> integrations = gitIntegrationService.findAll();
        model.addAttribute("integrations", integrations);
        model.addAttribute("activeNav", "git-integrations");
        return "git-integrations/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("integration", new GitIntegration());
        model.addAttribute("providerTypes", RepositoryType.values());
        model.addAttribute("postReviewActions", PostReviewAction.values());
        model.addAttribute("activeNav", "git-integrations");
        return "git-integrations/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return gitIntegrationService.findById(id)
                .map(integration -> {
                    model.addAttribute("integration", integration);
                    model.addAttribute("providerTypes", RepositoryType.values());
                    model.addAttribute("postReviewActions", PostReviewAction.values());
                    model.addAttribute("activeNav", "git-integrations");
                    return "git-integrations/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Git Integration not found");
                    return "redirect:/git-integrations";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute GitIntegration integration, RedirectAttributes redirectAttributes) {
        try {
            gitIntegrationService.save(integration);
            redirectAttributes.addFlashAttribute("success", "Git Integration saved successfully");
        } catch (Exception e) {
            log.error("Failed to save Git Integration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save: " + e.getMessage());
        }
        return "redirect:/git-integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            gitIntegrationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Git Integration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete Git Integration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/git-integrations";
    }
}
