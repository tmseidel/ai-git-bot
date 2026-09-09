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
        gitIntegrationRepository.deleteById(id);
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
