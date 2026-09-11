package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class GitIntegrationService {

    private final GitIntegrationRepository gitIntegrationRepository;
    private final EncryptionService encryptionService;
    private final BotRepository botRepository;

    @Transactional(readOnly = true)
    public List<GitIntegration> findAll() {
        return gitIntegrationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<GitIntegration> findById(Long id) {
        return gitIntegrationRepository.findById(id);
    }

    /**
     * Saves a Git integration, resolving the token from the form input.
     *
     * <p>The token field is a one-way write: the stored value is never echoed
     * back into the form. A blank field therefore means "keep the stored
     * value", while {@code clearToken} requests explicit removal (the Clear
     * button in the UI). Re-encrypting the kept ciphertext would corrupt the
     * token, so only freshly provided plaintext tokens are encrypted.</p>
     */
    public GitIntegration save(GitIntegration integration, boolean clearToken) {
        return save(integration, clearToken, false);
    }

    /**
     * Saves a Git integration including one-way SSH credential inputs.
     *
     * <p>SSH private keys and {@code known_hosts} follow the same one-way rule
     * as the token: blank fields keep the stored values, and only freshly
     * provided private keys are encrypted. Switching away from SSH transport
     * always clears both stored SSH credentials, no matter which clear flags
     * the caller supplied.</p>
     */
    public GitIntegration save(GitIntegration integration, boolean clearToken, boolean clearSshCredentials) {
        applyProviderDefaults(integration);
        if (integration.getTransport() == null) {
            integration.setTransport(GitTransport.HTTP);
        }
        GitIntegration existing = integration.getId() == null ? null
                : gitIntegrationRepository.findById(integration.getId()).orElse(null);
        validate(integration, existing, clearToken, clearSshCredentials);

        if (existing != null && existing.hasManagedSshKeyTracking()
                && (integration.getTransport() != GitTransport.SSH || clearSshCredentials || clearToken
                    || !isBlank(integration.getToken()) || !isBlank(integration.getSshPrivateKey())
                    || existing.getProviderType() != integration.getProviderType()
                    || !Objects.equals(existing.getUrl(), integration.getUrl()))) {
            throw new IllegalStateException("Remove the managed SSH key before changing its credentials");
        }

        GitIntegration current = existing == null ? new GitIntegration() : existing;
        current.setName(integration.getName());
        current.setProviderType(integration.getProviderType());
        current.setUrl(integration.getUrl());
        current.setUsername(integration.getUsername());
        current.setTransport(integration.getTransport());
        current.setPostReviewAction(integration.getPostReviewAction());

        String token = integration.getToken();
        if (token != null && !token.isBlank()) {
            current.setToken(encryptionService.encrypt(token));
        } else if (clearToken) {
            current.setToken(null);
        }

        if (integration.getTransport() != GitTransport.SSH) {
            current.setSshPrivateKey(null);
            current.setSshKnownHosts(null);
            return gitIntegrationRepository.save(current);
        }

        String privateKey = integration.getSshPrivateKey();
        if (privateKey != null && !privateKey.isBlank()) {
            current.setSshPrivateKey(encryptionService.encrypt(privateKey));
        } else if (clearSshCredentials) {
            current.setSshPrivateKey(null);
        }

        String knownHosts = integration.getSshKnownHosts();
        if (knownHosts != null && !knownHosts.isBlank()) {
            current.setSshKnownHosts(knownHosts);
        } else if (clearSshCredentials) {
            current.setSshKnownHosts(null);
        }

        return gitIntegrationRepository.save(current);
    }

    public void deleteById(Long id) {
        validateDelete(id);
        gitIntegrationRepository.findById(id).ifPresent(integration -> {
            if (integration.hasManagedSshKeyTracking()) {
                throw new IllegalStateException("Remove the managed SSH key before deleting the integration");
            }
        });
        gitIntegrationRepository.deleteById(id);
    }

    /** Rejects deletion before remote cleanup if a bot still uses the integration. */
    @Transactional(readOnly = true)
    public void validateDelete(Long id) {
        if (botRepository.existsByGitIntegrationId(id)) {
            throw new IllegalStateException("Git Integration is still used by a bot");
        }
    }

    /** Validates form input before any irreversible remote cleanup. */
    @Transactional(readOnly = true)
    public void validateSave(GitIntegration integration, boolean clearToken, boolean clearSshCredentials) {
        applyProviderDefaults(integration);
        // Check persistence constraints before revoking a working remote key.
        if (isBlank(integration.getName()) || integration.getName().length() > 255
                || integration.getProviderType() == null || isBlank(integration.getUrl())
                || integration.getUrl().length() > 255 || integration.getPostReviewAction() == null
                || integration.getUsername() != null && integration.getUsername().length() > 255) {
            throw new IllegalArgumentException("Invalid Git integration fields");
        }
        if (!isBlank(integration.getToken()) && encryptionService.encrypt(integration.getToken()).length() > 255) {
            throw new IllegalArgumentException("API token exceeds the storage limit");
        }
        GitIntegration existing = integration.getId() == null ? null
                : gitIntegrationRepository.findById(integration.getId()).orElse(null);
        validate(integration, existing, clearToken, clearSshCredentials);
    }

    /** Commits HTTP-only state while retaining all remote tracking for retryable cleanup. */
    public GitIntegration prepareManagedSshKeyRemoval(Long id) {
        GitIntegration integration = requireIntegration(id);
        integration.setTransport(GitTransport.HTTP);
        integration.setSshPrivateKey(null);
        integration.setSshKnownHosts(null);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Clears tracking only after Gitea has confirmed remote removal. */
    public GitIntegration finishManagedSshKeyRemoval(Long id) {
        GitIntegration integration = requireIntegration(id);
        integration.setSshRemoteKeyId(null);
        integration.setSshRemoteKeyOwnerId(null);
        integration.setSshRemoteKeyTitle(null);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Commits an owner/title recovery marker before sending the registration request. */
    public GitIntegration prepareManagedSshKeyCreation(Long id, Long ownerId, String title) {
        GitIntegration integration = requireIntegration(id);
        if (integration.hasManagedSshKeyTracking()) {
            throw new IllegalStateException("Remove the previous managed SSH key first");
        }
        integration.setTransport(GitTransport.HTTP);
        integration.setSshPrivateKey(null);
        integration.setSshKnownHosts(null);
        integration.setSshRemoteKeyOwnerId(ownerId);
        integration.setSshRemoteKeyTitle(title);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Stores generated SSH credentials after successful Gitea registration. */
    public GitIntegration configureGeneratedSsh(Long id, String privateKey, String knownHosts,
                                                Long remoteKeyId, Long ownerId, String title) {
        if (!encryptionService.isEncryptionEnabled()) {
            throw new IllegalStateException("Automatic SSH setup requires APP_ENCRYPTION_KEY");
        }
        GitIntegration integration = requireIntegration(id);
        if (integration.getProviderType() != RepositoryType.GITEA || isBlank(privateKey)
                || isBlank(knownHosts) || remoteKeyId == null || remoteKeyId <= 0
                || !Objects.equals(ownerId, integration.getSshRemoteKeyOwnerId())
                || !Objects.equals(title, integration.getSshRemoteKeyTitle())) {
            throw new IllegalArgumentException("Invalid generated SSH configuration");
        }
        integration.setSshPrivateKey(encryptionService.encrypt(privateKey));
        integration.setSshKnownHosts(knownHosts);
        integration.setSshRemoteKeyId(remoteKeyId);
        integration.setTransport(GitTransport.SSH);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    private GitIntegration requireIntegration(Long id) {
        return gitIntegrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
    }

    public String decryptToken(GitIntegration integration) {
        String token = integration.getToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(token);
    }

    /** Decrypts the stored SSH private key for a Git command. */
    public String decryptSshPrivateKey(GitIntegration integration) {
        String privateKey = integration.getSshPrivateKey();
        if (isBlank(privateKey)) {
            return null;
        }
        return encryptionService.decrypt(privateKey);
    }

    /** Returns whether SSH private keys can be encrypted at rest. */
    @Transactional(readOnly = true)
    public boolean isEncryptionEnabled() {
        return encryptionService.isEncryptionEnabled();
    }

    private void validate(GitIntegration integration, GitIntegration existing,
                          boolean clearToken, boolean clearSshCredentials) {
        boolean duplicate = integration.getId() == null
                ? gitIntegrationRepository.existsByName(integration.getName())
                : gitIntegrationRepository.existsByNameAndIdNot(integration.getName(), integration.getId());
        if (duplicate) {
            throw new IllegalArgumentException("A Git Integration with this name already exists");
        }
        GitTransport transport = integration.getTransport() == null
                ? GitTransport.HTTP : integration.getTransport();
        boolean hasNewPrivateKey = !isBlank(integration.getSshPrivateKey());
        boolean hasNewKnownHosts = !isBlank(integration.getSshKnownHosts());
        boolean endpointChanged = existing != null
                && (existing.getProviderType() != integration.getProviderType()
                    || !Objects.equals(existing.getUrl(), integration.getUrl()));
        if (endpointChanged && isBlank(integration.getToken())) {
            throw new IllegalArgumentException("A new API token is required when changing the provider or URL");
        }
        if (transport != GitTransport.SSH) {
            return;
        }
        if (!encryptionService.isEncryptionEnabled()) {
            throw new IllegalStateException("SSH private keys require APP_ENCRYPTION_KEY");
        }
        if (integration.getProviderType() != RepositoryType.GITEA) {
            throw new IllegalArgumentException("SSH transport is currently supported for Gitea integrations only");
        }
        if (endpointChanged && !hasNewKnownHosts) {
            throw new IllegalArgumentException("New verified known_hosts are required when changing the SSH endpoint");
        }
        String privateKey = hasNewPrivateKey ? integration.getSshPrivateKey()
                : clearSshCredentials || existing == null ? null : existing.getSshPrivateKey();
        String knownHosts = hasNewKnownHosts ? integration.getSshKnownHosts()
                : clearSshCredentials || existing == null ? null : existing.getSshKnownHosts();
        if (isBlank(privateKey) || isBlank(knownHosts)) {
            throw new IllegalArgumentException("SSH private key and known_hosts are required for SSH transport");
        }
        String token = !isBlank(integration.getToken()) ? integration.getToken()
                : clearToken || existing == null ? null : existing.getToken();
        if (isBlank(token)) {
            throw new IllegalArgumentException("API token is required for SSH transport");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void applyProviderDefaults(GitIntegration integration) {
        if (integration.getProviderType() == RepositoryType.GITHUB) {
            integration.setUrl("https://github.com");
        } else if (integration.getProviderType() == RepositoryType.BITBUCKET) {
            integration.setUrl("https://bitbucket.org");
        }
    }
}
