package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@Controller
@RequestMapping("/git-integrations")
public class GitIntegrationController {

    private final GitIntegrationService gitIntegrationService;
    private final MessageSource messageSource;

    public GitIntegrationController(GitIntegrationService gitIntegrationService, MessageSource messageSource) {
        this.gitIntegrationService = gitIntegrationService;
        this.messageSource = messageSource;
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
        model.addAttribute("transportTypes", GitTransport.values());
        model.addAttribute("postReviewActions", PostReviewAction.values());
        model.addAttribute("sshEncryptionEnabled", gitIntegrationService.isEncryptionEnabled());
        model.addAttribute("activeNav", "git-integrations");
        return "git-integrations/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return gitIntegrationService.findById(id)
                .map(integration -> {
                    model.addAttribute("integration", integration);
                    model.addAttribute("providerTypes", RepositoryType.values());
                    model.addAttribute("transportTypes", GitTransport.values());
                    model.addAttribute("postReviewActions", PostReviewAction.values());
                    model.addAttribute("sshEncryptionEnabled", gitIntegrationService.isEncryptionEnabled());
                    model.addAttribute("activeNav", "git-integrations");
                    return "git-integrations/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.gitNotFound", null, LocaleContextHolder.getLocale()));
                    return "redirect:/git-integrations";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute GitIntegration integration,
                       @RequestParam(required = false) String token,
                       @RequestParam(required = false, defaultValue = "false") boolean clearToken,
                       @RequestParam(required = false) String sshPrivateKey,
                       @RequestParam(required = false) String sshKnownHosts,
                       @RequestParam(required = false, defaultValue = "false") boolean clearSshCredentials,
                       RedirectAttributes redirectAttributes) {
        try {
            // The token form field is a one-way write: only override when a new
            // token is provided. Blank means "keep the stored token" and the
            // explicit Clear button requests removal - both resolved in the
            // service so the kept ciphertext is never re-encrypted.
            if (token != null && !token.isBlank()) {
                integration.setToken(token);
            }
            // SSH credentials follow the same one-way rule and are cleared by
            // the service whenever the transport leaves SSH.
            if (integration.getTransport() == GitTransport.SSH) {
                if (sshPrivateKey != null && !sshPrivateKey.isBlank()) {
                    integration.setSshPrivateKey(sshPrivateKey);
                }
                if (sshKnownHosts != null && !sshKnownHosts.isBlank()) {
                    integration.setSshKnownHosts(sshKnownHosts);
                }
            } else {
                integration.setSshPrivateKey(null);
                integration.setSshKnownHosts(null);
            }
            gitIntegrationService.save(integration, clearToken, clearSshCredentials);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitSaved", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to save Git Integration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            gitIntegrationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete Git Integration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations";
    }
}
