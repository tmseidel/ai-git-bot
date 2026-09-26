package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitDiffServiceTest {

    @TempDir
    Path tempDir;

    private Path origin;
    private Path workspaces;
    private GitDiffService service;
    private RepositoryApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        origin = Files.createDirectory(tempDir.resolve("origin"));
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");
        // Local upload-pack only serves unadvertised commits when allowed to.
        git("config", "uploadpack.allowAnySHA1InWant", "true");

        workspaces = tempDir.resolve("workspaces");
        service = new GitDiffService(new WorkspaceService(workspaces.toString()));
        client = mock(RepositoryApiClient.class);
        when(client.getRepositoryRemote("owner", "repo")).thenReturn(origin.toUri().toString());
        when(client.getCredentials()).thenReturn(
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "token"));
    }

    @Test
    void diffCommits_rendersGitsDiffIncludingRenames() throws Exception {
        write("a.txt", "one\ntwo\nthree\n");
        write("b.txt", "keep\nthis\ncontent\nas\nis\n");
        String base = commit();
        write("a.txt", "one\nchanged\nthree\n");
        git("mv", "b.txt", "c.txt");
        String head = commit();

        String diff = service.diffCommits(client, "owner", "repo", base, head);

        assertThat(diff)
                .contains("diff --git a/a.txt b/a.txt", "-two", "+changed")
                .contains("rename from b.txt", "rename to c.txt")
                .doesNotContain("Diff truncated");
        assertNoWorkspaceLeft();
    }

    @Test
    void read_cutsAnOversizedDiffAtAFileBoundaryAndCountsTheRest() throws Exception {
        String block = "+" + "x".repeat(GitDiffService.MAX_DIFF_BYTES / 3) + "\n";
        Path diffFile = tempDir.resolve("changes.diff");
        Files.writeString(diffFile, "diff --git a/1 b/1\n" + block
                + "diff --git a/2 b/2\n" + block
                + "diff --git a/3 b/3\n" + block
                + "diff --git a/4 b/4\n+small\n");

        String diff = GitDiffService.read(diffFile);

        assertThat(diff)
                .startsWith("diff --git a/1 b/1\n")
                .contains("diff --git a/2 b/2\n")
                .doesNotContain("diff --git a/3", "diff --git a/4")
                .endsWith("\nDiff truncated: 2 further files omitted\n");
    }

    @Test
    void diffCommits_returnsNullWhenACommitCannotBeFetched() throws Exception {
        write("README.md", "base\n");
        String head = commit();

        assertThat(service.diffCommits(client, "owner", "repo", "0".repeat(40), head)).isNull();
        assertNoWorkspaceLeft();
    }

    private void assertNoWorkspaceLeft() throws IOException {
        try (Stream<Path> entries = Files.list(workspaces)) {
            assertThat(entries).isEmpty();
        }
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(origin.resolve(name), content);
    }

    private String commit() throws Exception {
        git("add", "-A");
        git("commit", "-q", "-m", "change");
        return git("rev-parse", "HEAD").trim();
    }

    private String git(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(origin.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
