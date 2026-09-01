package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Objects;

@Slf4j
@Controller
@RequestMapping("/git-integrations")
public class GitIntegrationController {

    private final GitIntegrationService gitIntegrationService;
    private final GiteaSshSetupService giteaSshSetupService;
    private final MessageSource messageSource;

    public GitIntegrationController(GitIntegrationService gitIntegrationService,
                                    GiteaSshSetupService giteaSshSetupService,
                                    MessageSource messageSource) {
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

    /** Shows the current SSH host keys without changing the integration or Gitea. */
    @GetMapping("/{id}/ssh/setup")
    public String previewSshSetup(@PathVariable Long id, Model model,
                                  RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("preview", giteaSshSetupService.preview(id));
            model.addAttribute("activeNav", "git-integrations");
            return "git-integrations/ssh-setup";
        } catch (Exception e) {
            log.error("Failed to preview automatic SSH setup for Git Integration {}", id, e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage(
                    "flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
            return "redirect:/git-integrations/" + id + "/edit";
        }
    }

    /** Applies automatic SSH setup after the operator confirms the scanned host keys. */
    @PostMapping("/{id}/ssh/setup")
    public synchronized String confirmSshSetup(@PathVariable Long id,
                                  @RequestParam String confirmation,
                                  @RequestParam(required = false, defaultValue = "false") boolean confirmed,
                                  RedirectAttributes redirectAttributes) {
        try {
            giteaSshSetupService.setup(id, confirmation, confirmed);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage(
                    "flash.gitSshSetup", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to configure SSH automatically for Git Integration {}", id, e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage(
                    "flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations/" + id + "/edit";
    }

    @PostMapping("/save")
    // ponytail: admin writes are rare; use a distributed lock if this app runs on multiple nodes.
    public synchronized String save(@ModelAttribute GitIntegration integration,
                       @RequestParam(required = false) String token,
                       @RequestParam(required = false, defaultValue = "false") boolean clearToken,
                       @RequestParam(required = false) String sshPrivateKey,
                       @RequestParam(required = false) String sshKnownHosts,
                       @RequestParam(required = false, defaultValue = "false") boolean clearSshCredentials,
                       RedirectAttributes redirectAttributes) {
        GitIntegration existing = integration.getId() == null ? null
                : gitIntegrationService.findById(integration.getId()).orElse(null);
        try {
            // The token form field is a one-way write: only override when a new
            // token is provided. Blank means "keep the stored token" and the
            // explicit Clear button requests removal - both resolved in the
            // service so the kept ciphertext is never re-encrypted.
            if (token != null && !token.isBlank()) {
                integration.setToken(token);
            }
            boolean hasManagedKey = existing != null && (existing.getSshRemoteKeyId() != null
                    || existing.getSshRemoteKeyOwnerId() != null || existing.getSshRemoteKeyTitle() != null);
            boolean providerChanged = existing != null
                    && existing.getProviderType() != integration.getProviderType();
            boolean urlChanged = existing != null && !Objects.equals(existing.getUrl(), integration.getUrl());
            boolean tokenChanged = clearToken || token != null && !token.isBlank();
            boolean privateKeyChanged = sshPrivateKey != null && !sshPrivateKey.isBlank();
            boolean endpointCredentialsChanged = providerChanged || urlChanged || clearToken
                    || (hasManagedKey && tokenChanged);
            if (endpointCredentialsChanged && integration.getTransport() == GitTransport.SSH
                    && !privateKeyChanged) {
                integration.setTransport(GitTransport.HTTP);
            }
            if (integration.getTransport() == GitTransport.SSH) {
                if (privateKeyChanged) {
                    integration.setSshPrivateKey(sshPrivateKey);
                }
                if (sshKnownHosts != null && !sshKnownHosts.isBlank()) {
                    integration.setSshKnownHosts(sshKnownHosts);
                }
            } else {
                integration.setSshPrivateKey(null);
                integration.setSshKnownHosts(null);
                privateKeyChanged = false;
            }
            boolean transportDisabled = existing != null && existing.getTransport() == GitTransport.SSH
                    && integration.getTransport() != GitTransport.SSH;
            boolean pendingCleanup = hasManagedKey
                    && (existing.getSshRemoteKeyId() == null && existing.getSshRemoteKeyTitle() != null
                        || existing.getTransport() != GitTransport.SSH
                        || existing.getSshPrivateKey() == null || existing.getSshPrivateKey().isBlank());
            boolean clearStoredSsh = clearSshCredentials || (existing != null && privateKeyChanged)
                    || providerChanged || urlChanged || transportDisabled || (hasManagedKey && tokenChanged);
            boolean cleanupManagedKey = hasManagedKey && (pendingCleanup || clearStoredSsh);
            if (cleanupManagedKey) {
                gitIntegrationService.validateSave(integration, clearToken, clearStoredSsh);
                GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(existing.getId());
                String replacementToken = !providerChanged && !urlChanged && !clearToken ? token : null;
                if (!removeManagedKey(pending, replacementToken, redirectAttributes)) {
                    return "redirect:/git-integrations";
                }
                gitIntegrationService.finishManagedSshKeyRemoval(existing.getId());
            }
            gitIntegrationService.save(integration, clearToken, clearStoredSsh);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitSaved", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to save Git Integration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.saveFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
        }
        return "redirect:/git-integrations";
    }

    @PostMapping("/{id}/delete")
    public synchronized String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        GitIntegration existing = gitIntegrationService.findById(id).orElse(null);
        try {
            gitIntegrationService.validateDelete(id);
            if (existing != null && (existing.getSshRemoteKeyId() != null
                    || existing.getSshRemoteKeyOwnerId() != null || existing.getSshRemoteKeyTitle() != null)) {
                GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(id);
                if (!removeManagedKey(pending, null, redirectAttributes)) {
                    return "redirect:/git-integrations";
                }
            }
            gitIntegrationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.gitDeleted", null, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            log.error("Failed to delete Git Integration", e);
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.deleteFailed", new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
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
