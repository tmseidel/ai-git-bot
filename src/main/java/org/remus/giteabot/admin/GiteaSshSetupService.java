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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Coordinates confirmed host trust and remote registration across committed local stages. */
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

    /** Registers a new key only when a fresh scan matches the operator's confirmed preview. */
    public GitIntegration setup(Long integrationId, String expectedConfirmation, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("SSH host key confirmation is required");
        }
        SetupContext context = context(integrationId);
        SshCommandService.HostKeyScan hostKeys = sshCommandService.scanHostKeys(
                context.client().getAnySshCloneUrl());
        if (!hostKeys.confirmation().equals(expectedConfirmation)) {
            throw new IllegalStateException("SSH host keys changed; inspect and confirm them again");
        }
        long ownerId = context.client().getCurrentUserId();
        if (context.integration().hasManagedSshKeyTracking()) {
            GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(integrationId);
            deleteTrackedRemoteKeys(context.client(), pending);
            gitIntegrationService.finishManagedSshKeyRemoval(integrationId);
        }
        String title = "AI Git Bot: integration-" + integrationId + "-" + UUID.randomUUID();
        SshCommandService.SshKeyPair keyPair = sshCommandService.generateKeyPair(title);
        gitIntegrationService.prepareManagedSshKeyCreation(integrationId, ownerId, title);
        // A failed or lost response may still have created the key. Keep the marker for retry.
        long remoteKeyId = context.client().createSshKey(title, keyPair.publicKey());
        try {
            return gitIntegrationService.configureGeneratedSsh(integrationId, keyPair.privateKey(),
                    hostKeys.knownHosts(), remoteKeyId, ownerId, title);
        } catch (RuntimeException failure) {
            try {
                deleteRemoteKey(context.client(), remoteKeyId);
                gitIntegrationService.finishManagedSshKeyRemoval(integrationId);
            } catch (RuntimeException cleanupError) {
                log.warn("Failed to roll back tracked Gitea SSH key {} for integration {}", remoteKeyId, integrationId);
            }
            throw failure;
        }
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
                deleteTrackedRemoteKeys(requireGiteaClient(
                        giteaClientFactory.createApiClient(integration, replacementToken)), integration);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to remove Gitea SSH key {} for integration {}",
                    integration.getSshRemoteKeyId(), integration.getId());
            return false;
        }
    }

    private SetupContext context(Long id) {
        if (!gitIntegrationService.isEncryptionEnabled()) {
            throw new IllegalStateException("Automatic SSH setup requires APP_ENCRYPTION_KEY");
        }
        GitIntegration integration = gitIntegrationService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));
        if (integration.getProviderType() != RepositoryType.GITEA) {
            throw new IllegalArgumentException("Automatic SSH setup is supported for Gitea integrations only");
        }
        if (integration.getToken() == null || integration.getToken().isBlank()) {
            throw new IllegalArgumentException("A Gitea API token is required for automatic SSH setup");
        }
        if (integration.getTransport() == GitTransport.SSH
                && integration.getSshPrivateKey() != null && !integration.getSshPrivateKey().isBlank()) {
            throw new IllegalStateException(
                    "SSH is already configured; switch to HTTP and save before replacing the key");
        }
        return new SetupContext(integration, giteaClient(integration));
    }

    private GiteaApiClient giteaClient(GitIntegration integration) {
        return requireGiteaClient(giteaClientFactory.getApiClient(integration));
    }

    private GiteaApiClient requireGiteaClient(RepositoryApiClient client) {
        if (client instanceof GiteaApiClient giteaApiClient) {
            return giteaApiClient;
        }
        throw new IllegalStateException("The Git integration did not create a Gitea API client");
    }

    private void deleteTrackedRemoteKeys(GiteaApiClient client, GitIntegration integration) {
        Set<Long> remoteKeyIds = new LinkedHashSet<>();
        if (integration.getSshRemoteKeyOwnerId() != null
                && integration.getSshRemoteKeyOwnerId() != client.getCurrentUserId()) {
            throw new GiteaOwnerMismatchException();
        }
        if (integration.getSshRemoteKeyTitle() != null) {
            List<Long> titleMatches = client.getSshKeyIdsByTitle(integration.getSshRemoteKeyTitle());
            if (integration.getSshRemoteKeyId() == null) {
                remoteKeyIds.addAll(titleMatches);
            } else if (titleMatches.contains(integration.getSshRemoteKeyId())) {
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            } else if (client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
                throw new IllegalStateException("The tracked Gitea SSH key ID no longer matches its title");
            }
        } else if (integration.getSshRemoteKeyId() != null) {
            if (client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            }
        } else {
            throw new IllegalStateException("The managed SSH key marker has no recoverable ID or title");
        }
        for (Long remoteKeyId : remoteKeyIds) {
            deleteRemoteKey(client, remoteKeyId);
        }
    }

    private void deleteRemoteKey(GiteaApiClient client, long remoteKeyId) {
        try {
            client.deleteSshKey(remoteKeyId);
        } catch (HttpClientErrorException.NotFound ignored) {
            // Already absent remotely is the desired cleanup state.
        }
    }

    /** Read-only host-key details shown before the operator confirms setup. */
    public record SshSetupPreview(GitIntegration integration, String sshCloneUrl,
                                  SshCommandService.HostKeyScan hostKeys) { }

    private record SetupContext(GitIntegration integration, GiteaApiClient client) { }

    private static final class GiteaOwnerMismatchException extends IllegalStateException {
        private GiteaOwnerMismatchException() {
            super("The API token belongs to a different Gitea user");
        }
    }
}
