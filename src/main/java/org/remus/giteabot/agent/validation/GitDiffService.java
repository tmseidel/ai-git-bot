package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders the unified diff between two commits with {@code git} itself, for providers
 * whose API returns no patch text (Azure DevOps).
 * <p>
 * Each call fetches just the two commits ({@code --depth=1}) into a private temporary
 * workspace managed by {@link WorkspaceService}, which also supplies the authentication
 * and the scrubbed Git environment, and removes the workspace afterwards.
 * <p>
 * Fetching a commit by its SHA requires the server to serve objects no ref advertises
 * ({@code uploadpack.allowReachableSHA1InWant} or {@code uploadpack.allowAnySHA1InWant}
 * on a plain git server); git's own default refuses them.
 */
@Slf4j
@Service
public class GitDiffService {

    private static final String FILE_HEADER = "diff --git ";

    /**
     * Upper bound on the diff returned, as a memory guard: unlike the provider APIs, git
     * puts no limit on a diff. What reaches the model is bounded downstream by review
     * chunking.
     */
    static final int MAX_DIFF_BYTES = 8 * 1024 * 1024;

    private final WorkspaceService workspaceService;

    public GitDiffService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Returns {@code git diff --find-renames baseSha headSha} for the client's repository,
     * cut at a file boundary when it exceeds {@link #MAX_DIFF_BYTES}.
     *
     * @return the diff, or {@code null} when the commits could not be fetched or diffed
     */
    public String diffCommits(RepositoryApiClient repositoryClient, String owner, String repo,
                              String baseSha, String headSha) {
        try (Workspace workspace = workspaceService.createEmptyWorkspace(repositoryClient, owner, repo)) {
            CommandResult fetch = workspace.gitRemote(120, "fetch", "--depth=1", "--no-tags",
                    "--end-of-options", workspace.repositoryRemote(), baseSha, headSha);
            if (!fetch.success()) {
                return failed(owner, repo, "git fetch", fetch);
            }
            // Written to a file: git's warnings share the captured output stream, which is
            // also capped far below a large diff. The file itself is uncapped until read();
            // that is bounded in practice by the fetched snapshot already on disk next to
            // it, and binary files take a single line.
            Path diffFile = workspace.root().resolve("changes.diff");
            CommandResult diff = workspace.git(60, "-c", "core.quotePath=false", "diff", "--no-color",
                    "--no-ext-diff", "--no-textconv", "--find-renames",
                    "--output=" + diffFile.toAbsolutePath(), "--end-of-options", baseSha, headSha);
            if (!diff.success()) {
                return failed(owner, repo, "git diff", diff);
            }
            return read(diffFile);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to diff {}..{} in {}/{}: {}", baseSha, headSha, owner, repo, e.getMessage(), e);
            return null;
        }
    }

    private static String failed(String owner, String repo, String step, CommandResult result) {
        log.error("Failed to diff {}/{}: {} failed: {}", owner, repo, step, result.output());
        return null;
    }

    /**
     * Reads the diff, keeping only the whole files that fit into {@link #MAX_DIFF_BYTES}
     * and noting how many were left out. When even the first file exceeds the cap, only
     * that note is returned.
     */
    static String read(Path diffFile) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(diffFile)) {
            bytes = in.readNBytes(MAX_DIFF_BYTES + 1);
        }
        // Invalid UTF-8 (e.g. Latin-1 files) becomes U+FFFD, which is fine for the model.
        String diff = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length <= MAX_DIFF_BYTES) {
            return diff;
        }
        int cut = diff.lastIndexOf("\n" + FILE_HEADER);
        String kept = cut < 0 ? "" : diff.substring(0, cut + 1);
        int omitted = countFileHeaders(diffFile) - countFileHeaders(kept);
        return kept + "Diff truncated: " + omitted + " further files omitted\n";
    }

    private static int countFileHeaders(Path diffFile) throws IOException {
        // An InputStreamReader replaces malformed input rather than failing on it: diffs
        // of non-UTF-8 files are not valid UTF-8.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(diffFile), StandardCharsets.UTF_8))) {
            return (int) reader.lines().filter(line -> line.startsWith(FILE_HEADER)).count();
        }
    }

    private static int countFileHeaders(String diff) {
        return (int) diff.lines().filter(line -> line.startsWith(FILE_HEADER)).count();
    }
}
