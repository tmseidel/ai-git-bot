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
    public GitIntegration setup(Long integrationId, Long expectedVersion, String expectedConfirmation, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("SSH host key confirmation is required");
        }
        gitIntegrationService.requireActiveVersion(integrationId, expectedVersion);
        SetupContext context = context(integrationId);
        SshCommandService.HostKeyScan hostKeys = sshCommandService.scanHostKeys(
                context.client().getAnySshCloneUrl());
        if (!hostKeys.confirmation().equals(expectedConfirmation)) {
            throw new IllegalStateException("SSH host keys changed; inspect and confirm them again");
        }
        long ownerId = context.client().getCurrentUserId();
        GitIntegration current = gitIntegrationService.requireActiveVersion(integrationId, expectedVersion);
        if (context.integration().hasManagedSshKeyTracking()) {
            GitIntegration pending = gitIntegrationService.prepareManagedSshKeyRemoval(integrationId, expectedVersion);
            current = removeManagedKey(pending, null);
            if (current == null) {
                throw new IllegalStateException(
                        "Previous managed SSH key could not be removed from Gitea and may still be "
                                + "registered; retry the cleanup before setting up a new key");
            }
        }
        String title = "AI Git Bot: integration-" + integrationId + "-" + UUID.randomUUID();
        SshCommandService.SshKeyPair keyPair = sshCommandService.generateKeyPair(title);
        GitIntegration marker = gitIntegrationService.prepareManagedSshKeyCreation(
                integrationId, current.getLockVersion(), ownerId, title);
        Long markerVersion = marker.getLockVersion();
        // Keep the committed marker even if registration or the local commit has an ambiguous outcome.
        long[] remoteKeyId = {0};
        boolean[] dispatched = {false};
        try {
            return gitIntegrationService.withLockedVersion(integrationId, markerVersion, locked -> {
                gitIntegrationService.requireActiveVersion(integrationId, markerVersion);
                dispatched[0] = true;
                remoteKeyId[0] = context.client().createSshKey(title, keyPair.publicKey());
                return gitIntegrationService.configureGeneratedSsh(integrationId, markerVersion,
                        keyPair.privateKey(), hostKeys.knownHosts(), remoteKeyId[0], ownerId, title);
            });
        } catch (RuntimeException failure) {
            if (!dispatched[0]) {
                try {
                    gitIntegrationService.cancelUndispatchedSshCreation(integrationId, ownerId, title);
                } catch (RuntimeException cleanupError) {
                    log.warn("Failed to cancel undispatched SSH setup for integration {}", integrationId);
                }
            } else if (remoteKeyId[0] > 0) {
                try {
                    GitIntegration verified = gitIntegrationService.withLockedVersion(integrationId, markerVersion, locked -> {
                        locked.setSshCleanupVerified(true);
                        return locked;
                    });
                    Long cleanupVersion = verified.getLockVersion();
                    gitIntegrationService.withLockedVersion(integrationId, cleanupVersion, locked -> {
                        deleteRemoteKey(context.client(), remoteKeyId[0]);
                        return gitIntegrationService.finishManagedSshKeyRemoval(integrationId, cleanupVersion);
                    });
                } catch (RuntimeException cleanupError) {
                    log.warn("Failed to roll back tracked Gitea SSH key {} for integration {}", remoteKeyId[0], integrationId);
                }
            }
            throw failure;
        }
    }

    /** Removes a tracked key, using a replacement token only for the same Gitea owner. */
    public GitIntegration removeManagedKey(GitIntegration integration, String replacementToken) {
        GitIntegration verified = gitIntegrationService.withLockedVersion(integration.getId(), integration.getLockVersion(), locked -> {
            if (locked.getTransport() != GitTransport.HTTP || locked.getSshPrivateKey() != null) {
                throw new IllegalStateException("Disable SSH before remote cleanup");
            }
            if (!locked.hasManagedSshKeyTracking()) {
                return locked;
            }
            if (!removeRemoteManagedKey(locked, replacementToken, true)) {
                return null;
            }
            return locked;
        });
        if (verified == null) {
            return null;
        }
        // Commit evidence of a resolved title before DELETE. A failed local clear can then retry absence.
        return gitIntegrationService.withLockedVersion(verified.getId(), verified.getLockVersion(), locked -> {
            if (locked.hasManagedSshKeyTracking() && !removeRemoteManagedKey(locked, replacementToken, false)) {
                return null;
            }
            return gitIntegrationService.finishManagedSshKeyRemoval(locked.getId(), locked.getLockVersion());
        });
    }

    private boolean removeRemoteManagedKey(GitIntegration integration, String replacementToken, boolean verifyOnly) {
        try {
            try {
                deleteTrackedRemoteKeys(giteaClient(integration), integration, verifyOnly);
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
                        giteaClientFactory.createApiClient(integration, replacementToken)), integration, verifyOnly);
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
        if (integration.isDeletionPending()) {
            throw new IllegalStateException("Git Integration deletion is pending");
        }
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

    private void deleteTrackedRemoteKeys(GiteaApiClient client, GitIntegration integration, boolean verifyOnly) {
        Set<Long> remoteKeyIds = new LinkedHashSet<>();
        if (integration.getSshRemoteKeyOwnerId() != null
                && integration.getSshRemoteKeyOwnerId() != client.getCurrentUserId()) {
            throw new GiteaOwnerMismatchException();
        }
        if (integration.getSshRemoteKeyTitle() != null) {
            List<Long> titleMatches = client.getSshKeyIdsByTitle(integration.getSshRemoteKeyTitle());
            if (integration.getSshRemoteKeyId() == null) {
                // A timed-out POST may still finish upstream after our DB lock is released.
                // Absence alone cannot prove that an ambiguous registration was cancelled.
                if (titleMatches.isEmpty() && !integration.isSshCleanupVerified()) {
                    throw new IllegalStateException("SSH registration outcome is unresolved; retain its recovery marker");
                }
                remoteKeyIds.addAll(titleMatches);
                if (verifyOnly && !titleMatches.isEmpty()) {
                    integration.setSshCleanupVerified(true);
                }
            } else if (titleMatches.contains(integration.getSshRemoteKeyId())) {
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            } else if (client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
                // The ID is the stable handle; a renamed title is still our key.
                log.warn("Tracked Gitea SSH key {} has an unexpected title; removing it by ID",
                        integration.getSshRemoteKeyId());
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            }
        } else if (integration.getSshRemoteKeyId() != null) {
            if (client.getSshKeyIds().contains(integration.getSshRemoteKeyId())) {
                remoteKeyIds.add(integration.getSshRemoteKeyId());
            }
        } else {
            throw new IllegalStateException("The managed SSH key marker has no recoverable ID or title");
        }
        if (verifyOnly) {
            return;
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
