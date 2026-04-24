package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.model.FileChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceTest {

    private DiffApplyService diffApplyService;
    private WorkspaceService workspaceService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        diffApplyService = new DiffApplyService();
        workspaceService = new WorkspaceService(diffApplyService);
    }

    @Test
    void applyFileChangeToWorkspace_diffBasedUpdate_appliesDiffCorrectly() throws IOException {
        // Given: A workspace with an existing file
        Path existingFile = tempDir.resolve("src/main/java/Task.java");
        Files.createDirectories(existingFile.getParent());
        String originalContent = """
                package com.example;
                
                public class Task {
                    private Long id;
                    private String title;
                }
                """;
        Files.writeString(existingFile, originalContent);

        // A diff-based update that adds an assignee field
        String diff = """
                <<<<<<< SEARCH
                    private Long id;
                    private String title;
                =======
                    private Long id;
                    private String title;
                    private String assignee;
                >>>>>>> REPLACE
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/Task.java")
                .operation(FileChange.Operation.UPDATE)
                .diff(diff)
                .content("")
                .build();

        // When
        workspaceService.applyFileChangeToWorkspace(tempDir, change);

        // Then
        String result = Files.readString(existingFile);
        assertThat(result).contains("private String assignee;");
        assertThat(result).contains("private Long id;");
        assertThat(result).contains("private String title;");
        assertThat(result).isNotEmpty();
    }

    @Test
    void applyFileChangeToWorkspace_contentBasedUpdate_writesContent() throws IOException {
        // Given: A workspace with an existing file
        Path existingFile = tempDir.resolve("src/main/java/Task.java");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "old content");

        String newContent = """
                package com.example;
                
                public class Task {
                    private Long id;
                    private String title;
                    private String assignee;
                }
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/Task.java")
                .operation(FileChange.Operation.UPDATE)
                .content(newContent)
                .diff(null)
                .build();

        // When
        workspaceService.applyFileChangeToWorkspace(tempDir, change);

        // Then
        String result = Files.readString(existingFile);
        assertThat(result).isEqualTo(newContent);
    }

    @Test
    void applyFileChangeToWorkspace_createOperation_writesFullContent() throws IOException {
        String content = """
                package com.example;
                
                public class NewClass {
                }
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/NewClass.java")
                .operation(FileChange.Operation.CREATE)
                .content(content)
                .build();

        // When
        workspaceService.applyFileChangeToWorkspace(tempDir, change);

        // Then
        Path createdFile = tempDir.resolve("src/main/java/NewClass.java");
        assertThat(createdFile).exists();
        assertThat(Files.readString(createdFile)).isEqualTo(content);
    }

    @Test
    void applyFileChangeToWorkspace_deleteOperation_removesFile() throws IOException {
        // Given: existing file
        Path fileToDelete = tempDir.resolve("obsolete.txt");
        Files.writeString(fileToDelete, "delete me");

        FileChange change = FileChange.builder()
                .path("obsolete.txt")
                .operation(FileChange.Operation.DELETE)
                .build();

        // When
        workspaceService.applyFileChangeToWorkspace(tempDir, change);

        // Then
        assertThat(fileToDelete).doesNotExist();
    }

    @Test
    void applyFileChangeToWorkspace_diffBasedUpdate_missingFile_skips() throws IOException {
        // Diff-based update for a file that doesn't exist should not throw
        FileChange change = FileChange.builder()
                .path("nonexistent.java")
                .operation(FileChange.Operation.UPDATE)
                .diff("<<<<<<< SEARCH\nold\n=======\nnew\n>>>>>>> REPLACE")
                .content("")
                .build();

        workspaceService.applyFileChangeToWorkspace(tempDir, change);

        // File should not be created
        assertThat(tempDir.resolve("nonexistent.java")).doesNotExist();
    }

    @Test
    void cleanupWorkspace_deletesDirectory() throws IOException {
        Path wsDir = tempDir.resolve("workspace");
        Files.createDirectories(wsDir.resolve("sub"));
        Files.writeString(wsDir.resolve("sub/file.txt"), "content");

        workspaceService.cleanupWorkspace(wsDir);

        assertThat(wsDir).doesNotExist();
    }

    @Test
    void cleanupWorkspace_nullPath_doesNotThrow() {
        workspaceService.cleanupWorkspace(null);
        // no exception expected
    }

    @Test
    void buildCloneUrl_http() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "http://git.example.com", "mytoken");
        assertThat(url).isEqualTo("http://oauth2:mytoken@git.example.com/owner/repo.git");
    }

    @Test
    void buildCloneUrl_https_trailingSlash() {
        String url = workspaceService.buildCloneUrl("owner", "repo",
                "https://git.example.com/", "tok");
        assertThat(url).isEqualTo("https://oauth2:tok@git.example.com/owner/repo.git");
    }

    @Test
    void applyFileChangeToWorkspace_diffBasedUpdate_failedDiff_preservesOriginalContent() throws IOException {
        // Given: A workspace with an existing file
        Path existingFile = tempDir.resolve("src/main/java/Task.java");
        Files.createDirectories(existingFile.getParent());
        String originalContent = """
                package com.example;
                
                public class Task {
                    private Long id;
                    private String title;
                }
                """;
        Files.writeString(existingFile, originalContent);

        // A diff that does NOT match the actual file content
        String badDiff = """
                <<<<<<< SEARCH
                    private String nonExistentField;
                =======
                    private String replacedField;
                >>>>>>> REPLACE
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/Task.java")
                .operation(FileChange.Operation.UPDATE)
                .diff(badDiff)
                .content("")
                .build();

        // When / Then: DiffApplyException is thrown
        assertThatThrownBy(() -> workspaceService.applyFileChangeToWorkspace(tempDir, change))
                .isInstanceOf(DiffApplyService.DiffApplyException.class);

        // And the original file content is preserved
        String result = Files.readString(existingFile);
        assertThat(result).isEqualTo(originalContent);
    }
}

