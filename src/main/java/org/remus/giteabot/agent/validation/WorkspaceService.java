package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestHead;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Creates the {@link Workspace}s of the AI agent by cloning a repository into a private
 * temporary directory. Working in a workspace and cleaning it up is up to the workspace itself.
 */
@Slf4j
@Service
public class WorkspaceService {

    private final Path workspaceBaseDir;
    private final int cloneTimeoutSeconds;
    /** Every workspace whose directory still exists, so that a failed cleanup can be retried. */
    private final Set<Workspace> workspaces = ConcurrentHashMap.newKeySet();

    /**
     * Creates a service with a private workspace parent and the operator's clone
     * budget. Both values come from the configuration; the placeholders carry the
     * same defaults as {@code application.properties}, so a missing property needs
     * no defensive fallback here.
     */
    @Autowired
    public WorkspaceService(@Value("${giteabot.workspaces.dir:#{null}}") String configuredDir,
                            @Value("${giteabot.workspaces.clone-timeout-seconds:300}")
                            int cloneTimeoutSeconds) {
        this.workspaceBaseDir = configuredDir == null || configuredDir.isBlank()
                ? null
                : Path.of(configuredDir).toAbsolutePath().normalize();
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
    }

    /**
     * Clones a repository workspace. When a branch-based shallow clone fails and
     * {@code prNumber} is non-null, falls back to cloning the default branch then
     * fetching the provider's pull-request head ref
     * ({@link RepositoryApiClient#getPullRequestHeadRef}) — the fork-safe ref that
     * carries the head commit in the target repository.
     *
     * <p>The repository client resolves the credential-free remote and its
     * credentials once before any workspace is allocated. HTTP credentials use
     * a credential-store file outside the checkout; SSH credentials use private
     * key and {@code known_hosts} files in the same private temporary parent.</p>
     */
    public WorkspaceResult prepareWorkspace(RepositoryApiClient repositoryClient,
                                            String owner, String repo, String branch, Long prNumber) {
        final CheckoutConfig checkout;
        try {
            checkout = resolveCheckout(repositoryClient, owner, repo);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Failed to resolve repository checkout for {}/{}: {}", owner, repo, message, e);
            return WorkspaceResult.failure("Failed to resolve repository checkout: " + message);
        }
        final String repositoryRemote = checkout.remote();
        final RepositoryCredentials credentials = checkout.credentials();
        final boolean usesAuthorizationHeader = checkout.usesAuthorizationHeader();

        Workspace workspace = null;
        try {
            workspace = createWorkspace();
            Path workspaceDir = workspace.path();
            log.info("Cloning repository to {} for workspace", workspaceDir);

            workspace.setAuthentication(repositoryRemote, credentials, usesAuthorizationHeader);
            CommandResult cloneResult = workspace.gitRemote(workspace.root().toFile(),
                    cloneTimeoutSeconds,
                    "clone", "--depth", "1", "--branch", branch,
                    repositoryRemote, workspaceDir.getFileName().toString());

            if (cloneResult.success()) {
                return WorkspaceResult.success(workspace);
            }

            // Fork PR fallback: clone default branch → fetch PR head ref
            if (prNumber != null) {
                // The ref is provider-specific (refs/pull/… vs refs/merge-requests/…),
                // and the failure texts below are what an operator reads on the PR.
                String headRef = repositoryClient.getPullRequestHeadRef(prNumber);
                log.info("Branch '{}' is not in {}/{} (PR #{}), falling back to {} "
                                + "in this repository: {}", branch, owner, repo, prNumber, headRef,
                        cloneResult.output());
                // Tear down the failed attempt before starting the retry.
                if (!workspace.cleanup()) {
                    return WorkspaceResult.failure("Failed to clean up the initial clone attempt");
                }
                workspace = createWorkspace();
                workspaceDir = workspace.path();
                workspace.setAuthentication(repositoryRemote, credentials, usesAuthorizationHeader);

                CommandResult defaultCloneResult = workspace.gitRemote(workspace.root().toFile(),
                        cloneTimeoutSeconds,
                        "clone", "--depth", "1", repositoryRemote, workspaceDir.getFileName().toString());

                if (!defaultCloneResult.success()) {
                    log.error("Fallback clone (default branch) also failed: {}",
                            defaultCloneResult.output());
                    workspace.cleanup();
                    return WorkspaceResult.failure(
                            "Failed to clone " + owner + "/" + repo + " (branch '" + branch + "': "
                                    + cloneResult.output() + "; default branch: "
                                    + defaultCloneResult.output() + ")");
                }

                CommandResult fetchResult = workspace.gitRemote(cloneTimeoutSeconds,
                        "fetch", "origin", headRef);

                if (!fetchResult.success()) {
                    log.error("Failed to fetch PR head ref for PR #{}: {}", prNumber,
                            fetchResult.output());
                    workspace.cleanup();
                    return WorkspaceResult.failure(
                            "Failed to fetch " + headRef + " from " + owner + "/"
                                    + repo + ": " + fetchResult.output());
                }

                // Checking out a large tree can take as long as the clone that fetched it,
                // so it shares the clone budget instead of a fixed 15 s.
                CommandResult checkoutResult = workspace.git(cloneTimeoutSeconds,
                        "checkout", "-B", branch, "FETCH_HEAD");

                if (!checkoutResult.success()) {
                    log.error("Failed to checkout FETCH_HEAD for PR #{}: {}", prNumber,
                            checkoutResult.output());
                    workspace.cleanup();
                    return WorkspaceResult.failure(
                            "Failed to checkout " + headRef + " for PR #"
                                    + prNumber + ": " + checkoutResult.output());
                }

                return WorkspaceResult.success(workspace);
            }

            // No fallback — report the original clone error
            log.error("Failed to clone {}/{} branch '{}': {}", owner, repo, branch,
                    cloneResult.output());
            workspace.cleanup();
            return WorkspaceResult.failure("Failed to clone " + owner + "/" + repo + " branch '"
                    + branch + "': " + cloneResult.output());

        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            if (workspace != null) {
                workspace.cleanup();
            }
            return WorkspaceResult.failure("Failed to prepare workspace: " + e.getMessage());
        } catch (RuntimeException e) {
            if (workspace != null) {
                workspace.cleanup();
            }
            throw e;
        }
    }

    /**
     * Prepares a workspace that holds the pull-request head commit.
     *
     * <p>Providers that resolve the authoritative head clone the <em>source</em>
     * repository and branch, so a fork PR is checked out directly instead of
     * paying a branch clone against the target repository that can never
     * succeed — the contributor's branch does not live there. Providers without
     * that resolution keep the target-repository behaviour of
     * {@link #prepareWorkspace}, including its provider pull-request-ref
     * fallback.</p>
     *
     * <p>{@code mode} decides how much of the fallback chain is acceptable when
     * the source checkout does not work out: a {@link PullRequestWorkspaceMode#PUSH_BACK}
     * workspace must be a checkout of the head branch itself, so every
     * target-repository or PR-ref fallback fails closed; a
     * {@link PullRequestWorkspaceMode#READ_ONLY} workspace accepts the target
     * repository's PR ref, which carries the same commit while the PR is
     * open.</p>
     */
    public WorkspaceResult preparePullRequestWorkspace(RepositoryApiClient repositoryClient,
                                                       String owner, String repo,
                                                       String branch, Long prNumber,
                                                       PullRequestWorkspaceMode mode) {
        Objects.requireNonNull(mode, "mode");
        boolean mustPushBack = mode == PullRequestWorkspaceMode.PUSH_BACK;
        if (!repositoryClient.requiresAuthoritativePullRequestHead()) {
            return prepareWorkspace(repositoryClient, owner, repo, branch, prNumber);
        }
        final PullRequestHead head;
        try {
            head = resolvePullRequestHead(repositoryClient, owner, repo, branch, prNumber);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (mustPushBack) {
                log.error("Failed to resolve pull-request head for {}/{}#{}: {}",
                        owner, repo, prNumber, message, e);
                return WorkspaceResult.failure("Failed to resolve pull-request head: " + message);
            }
            // A read-only review loses nothing by reviewing the target repository's
            // PR ref: it carries the head commit even when the provider's head
            // lookup is unavailable or reports a stale branch.
            log.warn("Could not resolve the pull-request head of {}/{}#{} ({}); reviewing "
                            + "{} in the target repository instead",
                    owner, repo, prNumber, message,
                    repositoryClient.getPullRequestHeadRef(prNumber));
            return prepareWorkspace(repositoryClient, owner, repo, branch, prNumber);
        }

        boolean sameRepository = owner.equalsIgnoreCase(head.owner())
                && repo.equalsIgnoreCase(head.repository());
        // Only a read-only same-repository PR may fall back to its own
        // refs/pull/<n>/head inside prepareWorkspace: for a fork head that ref
        // belongs to the target repository, and a PUSH_BACK workspace may use
        // neither.
        WorkspaceResult result = prepareWorkspace(repositoryClient, head.owner(), head.repository(),
                head.branch(), !mustPushBack && sameRepository ? prNumber : null);
        if (result.success() || mustPushBack || sameRepository || prNumber == null) {
            return result;
        }

        log.info("Could not check out head branch '{}' of {}/{} for PR #{}, falling back to "
                        + "{} in {}/{}: {}", head.branch(), head.owner(),
                head.repository(), prNumber, repositoryClient.getPullRequestHeadRef(prNumber),
                owner, repo, result.error());
        WorkspaceResult fallback = prepareWorkspace(repositoryClient, owner, repo, head.branch(),
                prNumber);
        if (fallback.success()) {
            return fallback;
        }
        return WorkspaceResult.failure("Failed to prepare a workspace for PR #" + prNumber
                + " (head " + head.owner() + "/" + head.repository() + " branch '" + head.branch()
                + "': " + result.error() + "; " + owner + "/" + repo + " "
                + repositoryClient.getPullRequestHeadRef(prNumber)
                + ": " + fallback.error() + ")");
    }

    /**
     * Returns whether an authoritative PR head belongs to another repository.
     * Providers without authoritative head resolution retain their existing
     * offer-as-PR behaviour and return {@code false}.
     */
    public boolean isAuthoritativePullRequestFromFork(RepositoryApiClient repositoryClient,
                                                      String owner, String repo,
                                                      String branch, Long prNumber) {
        if (!repositoryClient.requiresAuthoritativePullRequestHead()) {
            return false;
        }
        PullRequestHead head = repositoryClient.getPullRequestHead(owner, repo, prNumber, branch);
        return !owner.equalsIgnoreCase(head.owner()) || !repo.equalsIgnoreCase(head.repository());
    }

    /**
     * Creates a workspace without a checkout: an initialized repository that knows how to
     * authenticate against the remote, for commands that fetch only what they need.
     *
     * @throws IOException      when the workspace cannot be created or initialized
     * @throws RuntimeException when the repository client cannot resolve the checkout
     */
    Workspace createEmptyWorkspace(RepositoryApiClient repositoryClient, String owner, String repo)
            throws IOException {
        CheckoutConfig checkout = resolveCheckout(repositoryClient, owner, repo);
        Workspace workspace = createWorkspace();
        try {
            workspace.setAuthentication(checkout.remote(), checkout.credentials(), checkout.usesAuthorizationHeader());
            CommandResult init = GitCommandRunner.run(workspace.root().toFile(),
                    new String[]{"git", "init", "-q", workspace.path().getFileName().toString()}, 15);
            if (!init.success()) {
                throw new IOException("git init failed: " + init.output());
            }
            return workspace;
        } catch (IOException | RuntimeException e) {
            workspace.cleanup();
            throw e;
        }
    }

    // ---- internal helpers ------------------------------------------------

    /**
     * Resolves the credential-free remote, the credentials and the transport mode
     * of one checkout. Runs before a workspace is allocated, so a provider failure
     * leaves no directory behind.
     */
    private CheckoutConfig resolveCheckout(RepositoryApiClient repositoryClient,
                                           String owner, String repo) {
        String repositoryRemote = repositoryClient.getRepositoryRemote(owner, repo);
        RepositoryCredentials credentials = repositoryClient.getCredentials();
        boolean usesAuthorizationHeader = repositoryClient.usesGitAuthorizationHeader();
        if (repositoryRemote == null || repositoryRemote.isBlank() || credentials == null) {
            throw new IllegalStateException("Repository client returned incomplete checkout configuration");
        }
        return new CheckoutConfig(repositoryRemote, credentials, usesAuthorizationHeader);
    }

    /** One checkout's credential-free remote, credentials and transport mode. */
    private record CheckoutConfig(String remote, RepositoryCredentials credentials,
                                  boolean usesAuthorizationHeader) {
    }

    /**
     * Resolves the authoritative head of a pull request, rejecting any head whose
     * coordinates are incomplete — the callers clone from those coordinates.
     */
    private PullRequestHead resolvePullRequestHead(RepositoryApiClient repositoryClient,
                                                   String owner, String repo,
                                                   String branch, Long prNumber) {
        PullRequestHead head = repositoryClient.getPullRequestHead(owner, repo, prNumber, branch);
        if (head == null || head.owner() == null || head.owner().isBlank()
                || head.repository() == null || head.repository().isBlank()
                || head.branch() == null || head.branch().isBlank()) {
            throw new IllegalStateException("Repository client returned an incomplete pull-request head");
        }
        return head;
    }

    /**
     * Creates a new workspace with a private temporary parent.
     */
    Workspace createWorkspace() throws IOException {
        retryFailedCleanups();
        Path workspaceRoot;
        if (workspaceBaseDir == null) {
            workspaceRoot = Files.createTempDirectory("agent-workspace-");
        } else {
            Files.createDirectories(workspaceBaseDir);
            workspaceRoot = Files.createTempDirectory(workspaceBaseDir, "agent-workspace-");
        }
        Workspace workspace = newWorkspace(workspaceRoot, workspaces::remove);
        workspaces.add(workspace);
        try {
            Workspace.restrictToOwner(workspaceRoot, true);
            return workspace;
        } catch (IOException | RuntimeException e) {
            workspace.cleanup();
            throw e;
        }
    }

    Workspace newWorkspace(Path workspaceRoot, Consumer<Workspace> onCleanedUp) {
        return new Workspace(workspaceRoot, onCleanedUp);
    }

    private void retryFailedCleanups() {
        for (Workspace workspace : workspaces) {
            if (workspace.closed()) {
                workspace.cleanup();
            }
        }
    }
}
