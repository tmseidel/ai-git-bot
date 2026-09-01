package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class GitIntegrationService {

    private final GitIntegrationRepository gitIntegrationRepository;
    private final BotRepository botRepository;
    private final EncryptionService encryptionService;

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

    /** Saves one-way token and SSH credential inputs without echoing stored secrets into the form. */
    public GitIntegration save(GitIntegration integration, boolean clearToken, boolean clearSshCredentials) {
        // Set default URLs for providers that don't require user input
        if (integration.getProviderType() == RepositoryType.GITHUB) {
            integration.setUrl("https://github.com");
        } else if (integration.getProviderType() == RepositoryType.BITBUCKET) {
            integration.setUrl("https://bitbucket.org");
        }

        GitIntegration existing = integration.getId() == null
                ? null
                : gitIntegrationRepository.findById(integration.getId()).orElse(null);

        validateName(integration);
        if (integration.getTransport() == null) {
            integration.setTransport(GitTransport.HTTP);
        }
        if (integration.getTransport() != GitTransport.SSH) {
            integration.setSshPrivateKey(null);
            integration.setSshKnownHosts(null);
        }
        validate(integration, existing, clearToken, clearSshCredentials);

        String privateKey = integration.getSshPrivateKey();
        boolean hasNewPrivateKey = privateKey != null && !privateKey.isBlank();

        String token = integration.getToken();
        if (token != null && !token.isBlank()) {
            integration.setToken(encryptionService.encrypt(token));
        } else if (clearToken) {
            integration.setToken(null);
        } else if (existing != null) {
            integration.setToken(existing.getToken());
        }

        if (hasNewPrivateKey) {
            integration.setSshPrivateKey(encryptionService.encrypt(privateKey));
            integration.setSshRemoteKeyId(null);
            integration.setSshRemoteKeyOwnerId(null);
            integration.setSshRemoteKeyTitle(null);
        } else if (clearSshCredentials) {
            integration.setSshPrivateKey(null);
            integration.setSshRemoteKeyId(null);
            integration.setSshRemoteKeyOwnerId(null);
            integration.setSshRemoteKeyTitle(null);
        } else if (existing != null) {
            integration.setSshPrivateKey(existing.getSshPrivateKey());
            integration.setSshRemoteKeyId(existing.getSshRemoteKeyId());
            integration.setSshRemoteKeyOwnerId(existing.getSshRemoteKeyOwnerId());
            integration.setSshRemoteKeyTitle(existing.getSshRemoteKeyTitle());
        }

        String knownHosts = integration.getSshKnownHosts();
        if (knownHosts == null || knownHosts.isBlank()) {
            if (clearSshCredentials) {
                integration.setSshKnownHosts(null);
            } else if (existing != null) {
                integration.setSshKnownHosts(existing.getSshKnownHosts());
            }
        }

        return gitIntegrationRepository.save(integration);
    }

    /** Validates a form save without changing local or remote state. */
    @Transactional(readOnly = true)
    public void validateSave(GitIntegration integration, boolean clearToken, boolean clearSshCredentials) {
        GitIntegration existing = integration.getId() == null
                ? null
                : gitIntegrationRepository.findById(integration.getId()).orElse(null);
        validateName(integration);
        validate(integration, existing, clearToken, clearSshCredentials);
    }

    /** Disables SSH locally while retaining the remote key ID for retryable cleanup. */
    public GitIntegration prepareManagedSshKeyRemoval(Long id) {
        GitIntegration integration = gitIntegrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
        integration.setTransport(GitTransport.HTTP);
        integration.setSshPrivateKey(null);
        integration.setSshKnownHosts(null);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Clears the remote key ID after Gitea confirmed its removal. */
    public GitIntegration finishManagedSshKeyRemoval(Long id) {
        GitIntegration integration = gitIntegrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
        integration.setSshRemoteKeyId(null);
        integration.setSshRemoteKeyOwnerId(null);
        integration.setSshRemoteKeyTitle(null);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Persists a recoverable marker before creating a remote key. */
    public GitIntegration prepareManagedSshKeyCreation(Long id, Long remoteKeyOwnerId, String remoteKeyTitle) {
        GitIntegration integration = gitIntegrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
        integration.setSshRemoteKeyId(null);
        integration.setSshRemoteKeyOwnerId(remoteKeyOwnerId);
        integration.setSshRemoteKeyTitle(remoteKeyTitle);
        return gitIntegrationRepository.saveAndFlush(integration);
    }

    /** Stores an automatically generated SSH key after it was registered with Gitea. */
    public GitIntegration configureGeneratedSsh(Long id, String privateKey, String knownHosts,
                                                 Long remoteKeyId, Long remoteKeyOwnerId,
                                                 String remoteKeyTitle) {
        if (!encryptionService.isEncryptionEnabled()) {
            throw new IllegalStateException("Automatic SSH setup requires APP_ENCRYPTION_KEY");
        }
        GitIntegration integration = gitIntegrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
        if (integration.getProviderType() != RepositoryType.GITEA) {
            throw new IllegalArgumentException("Automatic SSH setup is supported for Gitea integrations only");
        }
        integration.setTransport(GitTransport.SSH);
        integration.setSshPrivateKey(encryptionService.encrypt(privateKey));
        integration.setSshKnownHosts(knownHosts);
        integration.setSshRemoteKeyId(remoteKeyId);
        integration.setSshRemoteKeyOwnerId(remoteKeyOwnerId);
        integration.setSshRemoteKeyTitle(remoteKeyTitle);
        return gitIntegrationRepository.save(integration);
    }

    /** Returns whether generated private keys can be encrypted at rest. */
    @Transactional(readOnly = true)
    public boolean isEncryptionEnabled() {
        return encryptionService.isEncryptionEnabled();
    }

    public void deleteById(Long id) {
        validateDelete(id);
        gitIntegrationRepository.deleteById(id);
    }

    /** Rejects deletion while a bot still references the integration. */
    @Transactional(readOnly = true)
    public void validateDelete(Long id) {
        if (botRepository.existsByGitIntegrationId(id)) {
            throw new IllegalStateException("Git Integration is still used by a bot");
        }
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

    private void validate(GitIntegration integration, GitIntegration existing,
                          boolean clearToken, boolean clearSshCredentials) {
        GitTransport transport = integration.getTransport() == null ? GitTransport.HTTP : integration.getTransport();
        boolean hasNewPrivateKey = !isBlank(integration.getSshPrivateKey());
        boolean endpointChanged = existing != null
                && (existing.getProviderType() != integration.getProviderType()
                    || !java.util.Objects.equals(existing.getUrl(), integration.getUrl()));
        if (endpointChanged && isBlank(integration.getToken())) {
            throw new IllegalArgumentException("A new API token is required when changing the provider or URL");
        }
        if ((transport == GitTransport.SSH || hasNewPrivateKey) && !encryptionService.isEncryptionEnabled()) {
            throw new IllegalStateException("SSH private keys require APP_ENCRYPTION_KEY");
        }
        if (transport != GitTransport.SSH) {
            return;
        }
        if (integration.getProviderType() != RepositoryType.GITEA) {
            throw new IllegalArgumentException("SSH transport is currently supported for Gitea integrations only");
        }
        String privateKey = hasNewPrivateKey ? integration.getSshPrivateKey()
                : clearSshCredentials || existing == null ? null : existing.getSshPrivateKey();
        String knownHosts = !isBlank(integration.getSshKnownHosts()) ? integration.getSshKnownHosts()
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

    private void validateName(GitIntegration integration) {
        boolean duplicate = integration.getId() == null
                ? gitIntegrationRepository.existsByName(integration.getName())
                : gitIntegrationRepository.existsByNameAndIdNot(integration.getName(), integration.getId());
        if (duplicate) {
            throw new IllegalArgumentException("A Git Integration with this name already exists");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
