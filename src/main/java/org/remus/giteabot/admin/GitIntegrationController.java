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
import java.util.Objects;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@Controller
@RequestMapping("/git-integrations")
public class GitIntegrationController {

    private final GitIntegrationService gitIntegrationService;
    private final GiteaSshSetupService giteaSshSetupService;
    private final MessageSource messageSource;

    public GitIntegrationController(GitIntegrationService gitIntegrationService,
                                    GiteaSshSetupService giteaSshSetupService, MessageSource messageSource) {
        this.gitIntegrationService = gitIntegrationService;
        this.giteaSshSetupService = giteaSshSetupService;
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

    /** Shows host fingerprints without changing local or remote configuration. */
    @GetMapping("/{id}/ssh/setup")
    public String previewSshSetup(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("preview", giteaSshSetupService.preview(id));
            model.addAttribute("activeNav", "git-integrations");
            return "git-integrations/ssh-setup";
        } catch (Exception e) {
            log.error("Failed to preview SSH setup for Git Integration {}", id);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage(
                    "flash.saveFailed", new Object[]{"SSH host-key preview failed"}, LocaleContextHolder.getLocale()));
            return "redirect:/git-integrations/" + id + "/edit";
        }
    }

    /** Generates and registers a key after explicit trusted-fingerprint confirmation. */
    @PostMapping("/{id}/ssh/setup")
    public String confirmSshSetup(@PathVariable Long id, @RequestParam String confirmation,
                                  @RequestParam(required = false, defaultValue = "false") boolean confirmed,
                                  RedirectAttributes redirectAttributes) {
        try {
            giteaSshSetupService.setup(id, confirmation, confirmed);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage(
                    "flash.gitSshSetup", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to configure SSH for Git Integration {}", id);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage(
                    "flash.saveFailed", new Object[]{"SSH setup failed; verify the saved configuration and host fingerprints before retrying"}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations/" + id + "/edit";
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
            GitIntegration existing = integration.getId() == null ? null
                    : gitIntegrationService.findById(integration.getId()).orElse(null);
            if (existing != null && existing.hasManagedSshKeyTracking()) {
                boolean endpointChanged = existing.getProviderType() != integration.getProviderType()
                        || !Objects.equals(existing.getUrl(), integration.getUrl());
                boolean tokenChanged = clearToken || token != null && !token.isBlank();
                boolean privateKeyChanged = integration.getSshPrivateKey() != null
                        && !integration.getSshPrivateKey().isBlank();
                boolean pendingCleanup = existing.getTransport() != GitTransport.SSH
                        || existing.getSshRemoteKeyId() == null
                        || existing.getSshPrivateKey() == null || existing.getSshPrivateKey().isBlank();
                boolean cleanup = pendingCleanup || endpointChanged || tokenChanged || privateKeyChanged
                        || clearSshCredentials || integration.getTransport() != GitTransport.SSH;
                if (cleanup) {
                    // Managed keys cannot be reused after removal. Manual-key edits do not enter this path.
                    if (privateKeyChanged && !endpointChanged && !clearSshCredentials
                            && (integration.getSshKnownHosts() == null || integration.getSshKnownHosts().isBlank())) {
                        integration.setSshKnownHosts(existing.getSshKnownHosts());
                    }
                    gitIntegrationService.validateSave(integration, clearToken, true);
                    GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(existing.getId());
                    String replacementToken = !endpointChanged && !clearToken ? token : null;
                    if (!removeManagedKey(pending, replacementToken, redirectAttributes)) {
                        return "redirect:/git-integrations";
                    }
                    gitIntegrationService.finishManagedSshKeyRemoval(existing.getId());
                    clearSshCredentials = true;
                }
            }
            gitIntegrationService.save(integration, clearToken, clearSshCredentials);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitSaved", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to save Git Integration {}", integration.getId());
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.saveFailed",
                    new Object[]{"Git integration could not be saved; check the submitted fields and credentials"}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            gitIntegrationService.validateDelete(id);
            GitIntegration existing = gitIntegrationService.findById(id).orElse(null);
            if (existing != null && existing.hasManagedSshKeyTracking()) {
                GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(id);
                if (!removeManagedKey(pending, null, redirectAttributes)) {
                    return "redirect:/git-integrations";
                }
                gitIntegrationService.finishManagedSshKeyRemoval(id);
            }
            gitIntegrationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete Git Integration {}", id);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed",
                    new Object[]{"Git integration could not be deleted; check bot assignments and managed-key cleanup"}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations";
    }

    private boolean removeManagedKey(GitIntegration integration, String replacementToken,
                                     RedirectAttributes redirectAttributes) {
        if (!giteaSshSetupService.removeManagedKey(integration, replacementToken)) {
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage(
                    "flash.gitSshKeyCleanupFailed", null, LocaleContextHolder.getLocale()));
            return false;
        }
        return true;
    }
}
