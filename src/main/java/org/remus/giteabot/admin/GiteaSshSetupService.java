package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

/** Coordinates confirmed SSH host trust with Gitea user-key registration. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiteaSshSetupService {

    private final GitIntegrationService gitIntegrationService;
    private final GiteaClientFactory giteaClientFactory;
    private final SshCommandService sshCommandService;

    /** Scans the SSH endpoint without changing local or remote configuration. */
    public SshSetupPreview preview(Long integrationId) {
        SetupContext context = context(integrationId);
        String sshCloneUrl = context.client().getAnySshCloneUrl();
        return new SshSetupPreview(context.integration(), sshCloneUrl,
                sshCommandService.scanHostKeys(sshCloneUrl));
    }

    /** Generates and registers a key only when the current scan matches the confirmed preview. */
    public GitIntegration setup(Long integrationId, Long expectedVersion,
                                String expectedConfirmation, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("SSH host key confirmation is required");
        }
        SetupContext context = context(integrationId, expectedVersion);
        String sshCloneUrl = context.client().getAnySshCloneUrl();
        SshCommandService.HostKeyScan hostKeys = sshCommandService.scanHostKeys(sshCloneUrl);
        if (!hostKeys.confirmation().equals(expectedConfirmation)) {
            throw new IllegalStateException("SSH host keys changed; inspect and confirm them again");
        }

        GitIntegration current = gitIntegrationService.requireActiveVersion(integrationId, expectedVersion);
        long remoteKeyOwnerId = context.client().getCurrentUserId();
        Long currentVersion = current.getLockVersion();
        if (current.hasManagedSshKeyTracking()) {
            GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(
                    integrationId, currentVersion);
            deleteTrackedRemoteKeys(context.client(), pending);
            GitIntegration cleaned = gitIntegrationService.finishManagedSshKeyRemoval(
                    integrationId, pending.getLockVersion());
            currentVersion = cleaned.getLockVersion();
        }

        String title = keyTitle(integrationId);
        SshCommandService.SshKeyPair keyPair = sshCommandService.generateKeyPair(title);
        GitIntegration marker = gitIntegrationService.prepareManagedSshKeyCreation(
                integrationId, currentVersion, remoteKeyOwnerId, title);
        long newRemoteKeyId = context.client().createSshKey(title, keyPair.publicKey());
        try {
            return gitIntegrationService.configureGeneratedSsh(
                    integrationId, marker.getLockVersion(), keyPair.privateKey(),
                    hostKeys.knownHosts(), newRemoteKeyId,
                    remoteKeyOwnerId, title);
        } catch (RuntimeException e) {
            rollbackTrackedKey(context.client(), integrationId, marker.getLockVersion(), newRemoteKeyId);
            throw e;
        }
    }

    /** Removes a tracked public key from Gitea, treating an already absent key as success. */
    public boolean removeManagedKey(GitIntegration integration) {
        return removeManagedKey(integration, null);
    }

    /** Removes a tracked key, using a replacement token only for the same Gitea owner. */
    public boolean removeManagedKey(GitIntegration integration, String replacementToken) {
        if (integration == null || !integration.hasManagedSshKeyTracking()) {
            return true;
        }
        try {
            try {
                deleteTrackedRemoteKeys(giteaClient(integration), integration);
            } catch (RuntimeException e) {
                boolean authenticationFailed = e instanceof GiteaOwnerMismatchException
                        || e instanceof HttpClientErrorException httpError
                        && (httpError.getStatusCode() == HttpStatus.UNAUTHORIZED
                            || httpError.getStatusCode() == HttpStatus.FORBIDDEN);
                if (!authenticationFailed || replacementToken == null || replacementToken.isBlank()
                        || integration.getSshRemoteKeyOwnerId() == null) {
                    throw e;
                }
                GiteaApiClient replacementClient = giteaClient(integration, replacementToken);
                deleteTrackedRemoteKeys(replacementClient, integration);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to remove Gitea SSH key {} for integration '{}': {}",
                    integration.getSshRemoteKeyId(), integration.getName(), e.getMessage());
            return false;
        }
    }

    private SetupContext context(Long integrationId) {
        requireEncryption();
        return context(gitIntegrationService.findById(integrationId)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found")));
    }

    private SetupContext context(Long integrationId, Long expectedVersion) {
        requireEncryption();
        return context(gitIntegrationService.requireActiveVersion(integrationId, expectedVersion));
    }

    private void requireEncryption() {
        if (!gitIntegrationService.isEncryptionEnabled()) {
            throw new IllegalStateException("Automatic SSH setup requires APP_ENCRYPTION_KEY");
        }
    }

    private SetupContext context(GitIntegration integration) {
        if (integration.isDeletionPending()) {
            throw new IllegalStateException("Git Integration deletion is pending");
        }
        if (integration.getProviderType() != RepositoryType.GITEA) {
            throw new IllegalArgumentException("Automatic SSH setup is supported for Gitea integrations only");
        }
        if (integration.getToken() == null || integration.getToken().isBlank()) {
            throw new IllegalArgumentException("A Gitea API token is required for automatic SSH setup");
        }
        rejectConfiguredSsh(integration);
        return new SetupContext(integration, giteaClient(integration));
    }

    private GiteaApiClient giteaClient(GitIntegration integration) {
        return requireGiteaClient(giteaClientFactory.getApiClient(integration));
    }

    private GiteaApiClient giteaClient(GitIntegration integration, String replacementToken) {
        return requireGiteaClient(giteaClientFactory.createApiClient(integration, replacementToken));
    }

    private GiteaApiClient requireGiteaClient(RepositoryApiClient client) {
        if (client instanceof GiteaApiClient giteaApiClient) {
            return giteaApiClient;
        }
        throw new IllegalStateException("The Git integration did not create a Gitea API client");
    }

    private void rejectConfiguredSsh(GitIntegration integration) {
        if (integration.getTransport() == GitTransport.SSH
                && integration.getSshPrivateKey() != null && !integration.getSshPrivateKey().isBlank()) {
            throw new IllegalStateException(
                    "SSH is already configured; switch to HTTP and save before replacing the key");
        }
    }

    private void deleteTrackedRemoteKeys(GiteaApiClient client, GitIntegration integration) {
        java.util.Set<Long> remoteKeyIds = new java.util.LinkedHashSet<>();
        if (integration.getSshRemoteKeyOwnerId() != null
                && integration.getSshRemoteKeyOwnerId() != client.getCurrentUserId()) {
            throw new GiteaOwnerMismatchException();
        }
        if (integration.getSshRemoteKeyTitle() != null) {
            java.util.List<Long> titleMatches = client.getSshKeyIdsByTitle(integration.getSshRemoteKeyTitle());
            if (integration.getSshRemoteKeyId() == null) {
                remoteKeyIds.addAll(titleMatches);
            } else if (titleMatches.contains(integration.getSshRemoteKeyId())) {
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            } else if (client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
                throw new IllegalStateException("The tracked Gitea SSH key ID no longer matches its title");
            }
        } else if (integration.getSshRemoteKeyId() != null
                && client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
            remoteKeyIds.add(integration.getSshRemoteKeyId());
        }
        for (Long remoteKeyId : remoteKeyIds) {
            deleteRemoteKey(client, remoteKeyId);
        }
    }

    private void rollbackTrackedKey(GiteaApiClient client, Long integrationId,
                                    Long expectedVersion, long remoteKeyId) {
        try {
            deleteRemoteKey(client, remoteKeyId);
            gitIntegrationService.finishManagedSshKeyRemoval(integrationId, expectedVersion);
        } catch (RuntimeException e) {
            log.warn("Failed to roll back tracked Gitea SSH key {}: {}", remoteKeyId, e.getMessage());
        }
    }

    private void deleteRemoteKey(GiteaApiClient client, long remoteKeyId) {
        try {
            client.deleteSshKey(remoteKeyId);
        } catch (HttpClientErrorException.NotFound ignored) {
            // Already absent remotely is the desired cleanup state.
        }
    }

    private String keyTitle(Long integrationId) {
        return "AI Git Bot: integration-" + integrationId + "-" + UUID.randomUUID();
    }

    /** Read-only host-key details shown before the operator confirms setup. */
    public record SshSetupPreview(GitIntegration integration, String sshCloneUrl,
                                  SshCommandService.HostKeyScan hostKeys) {
    }

    private record SetupContext(GitIntegration integration, GiteaApiClient client) {
    }

    private static final class GiteaOwnerMismatchException extends IllegalStateException {
        private GiteaOwnerMismatchException() {
            super("The API token belongs to a different Gitea user");
        }
    }
}
