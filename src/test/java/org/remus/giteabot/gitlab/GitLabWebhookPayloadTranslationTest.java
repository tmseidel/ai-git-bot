package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.model.CommentId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GitLabWebhookPayloadTranslationTest {

    private GitLabWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GitLabWebhookHandler(mock(BotWebhookService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"merge_request", "issue", "note"})
    void translateRepository_usesCanonicalPathInsteadOfDisplayNames(String hookType) {
        Map<String, Object> project = Map.of(
                "id", 1,
                "name", "Readable Project Name",
                "path", "project-slug",
                "path_with_namespace", "team/subgroup/project-slug",
                "namespace", "Administrator");
        Map<String, Object> attrs = Map.of("iid", 42, "noteable_type", "Issue");
        Map<String, Object> payload = Map.of("project", project, "object_attributes", attrs);

        WebhookPayload translated = switch (hookType) {
            case "merge_request" -> handler.translateMergeRequestPayload(payload, attrs);
            case "issue" -> handler.translateIssuePayload(payload, attrs);
            case "note" -> handler.translateNotePayload(payload, attrs);
            default -> throw new IllegalArgumentException(hookType);
        };

        assertEquals("team/subgroup", translated.getRepository().getOwner().getLogin());
        assertEquals("project-slug", translated.getRepository().getName());
        assertEquals("team/subgroup/project-slug", translated.getRepository().getFullName());
    }

    @Test
    void translateMergeRequestPayload_mapsAllFields() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", 100);
        attrs.put("iid", 1);
        attrs.put("title", "Test MR");
        attrs.put("description", "Some changes");
        attrs.put("state", "opened");
        attrs.put("source_branch", "feature");
        attrs.put("target_branch", "main");
        attrs.put("last_commit", Map.of("id", "abc123"));

        Map<String, Object> project = new HashMap<>();
        project.put("id", 1);
        project.put("name", "testrepo");
        project.put("path_with_namespace", "testowner/testrepo");
        project.put("namespace", Map.of("path", "testowner"));

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", project);
        gitlabPayload.put("user", Map.of("username", "testuser"));

        WebhookPayload result = handler.translateMergeRequestPayload(gitlabPayload, attrs);

        assertNotNull(result.getPullRequest());
        assertEquals(100L, result.getPullRequest().getId());
        assertEquals(1L, result.getPullRequest().getNumber());
        assertEquals("Test MR", result.getPullRequest().getTitle());
        assertEquals("Some changes", result.getPullRequest().getBody());
        assertEquals("open", result.getPullRequest().getState());
        assertEquals("feature", result.getPullRequest().getHead().getRef());
        assertEquals("abc123", result.getPullRequest().getHead().getSha());
        assertEquals("main", result.getPullRequest().getBase().getRef());

        assertNotNull(result.getRepository());
        assertEquals("testrepo", result.getRepository().getName());
        assertEquals("testowner/testrepo", result.getRepository().getFullName());
        assertEquals("testowner", result.getRepository().getOwner().getLogin());

        assertNotNull(result.getSender());
        assertEquals("testuser", result.getSender().getLogin());
    }

    @Test
    void translateMergeRequestPayload_closedState_mappedCorrectly() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", 100);
        attrs.put("iid", 1);
        attrs.put("title", "Closed MR");
        attrs.put("state", "closed");
        attrs.put("source_branch", "feature");
        attrs.put("target_branch", "main");

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", Map.of("id", 1, "name", "repo",
                "path_with_namespace", "owner/repo",
                "namespace", Map.of("path", "owner")));

        WebhookPayload result = handler.translateMergeRequestPayload(gitlabPayload, attrs);

        assertEquals("closed", result.getPullRequest().getState());
    }

    @Test
    void translateMergeRequestPayload_mergedState_mappedToClosed() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", 100);
        attrs.put("iid", 1);
        attrs.put("title", "Merged MR");
        attrs.put("state", "merged");
        attrs.put("source_branch", "feature");
        attrs.put("target_branch", "main");

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", Map.of("id", 1, "name", "repo",
                "path_with_namespace", "owner/repo",
                "namespace", Map.of("path", "owner")));

        WebhookPayload result = handler.translateMergeRequestPayload(gitlabPayload, attrs);

        assertEquals("closed", result.getPullRequest().getState());
    }

    @Test
    void translateNotePayload_mergeRequestNote_mapsAllFields() {
        Map<String, Object> noteAttrs = new HashMap<>();
        noteAttrs.put("id", 42);
        noteAttrs.put("note", "@bot please review");
        noteAttrs.put("noteable_type", "MergeRequest");
        noteAttrs.put("author", Map.of("username", "testuser"));

        Map<String, Object> mr = new HashMap<>();
        mr.put("id", 100);
        mr.put("iid", 1);
        mr.put("title", "Test MR");
        mr.put("description", "Some changes");
        mr.put("source_branch", "feature/progress-review");
        mr.put("target_branch", "main");
        mr.put("last_commit", Map.of("id", "abc123"));

        Map<String, Object> project = new HashMap<>();
        project.put("id", 1);
        project.put("name", "testrepo");
        project.put("path_with_namespace", "testowner/testrepo");
        project.put("namespace", Map.of("path", "testowner"));

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", noteAttrs);
        gitlabPayload.put("merge_request", mr);
        gitlabPayload.put("project", project);
        gitlabPayload.put("user", Map.of("username", "testuser"));

        WebhookPayload result = handler.translateNotePayload(gitlabPayload, noteAttrs);

        assertNotNull(result.getComment());
        assertEquals(CommentId.NumericCommentId.of(42L), result.getComment().getId());
        assertEquals("@bot please review", result.getComment().getBody());
        assertEquals("testuser", result.getComment().getUser().getLogin());

        assertNotNull(result.getIssue());
        assertEquals(1L, result.getIssue().getNumber());
        assertEquals("Test MR", result.getIssue().getTitle());

        assertNotNull(result.getPullRequest());
        assertEquals(1L, result.getPullRequest().getNumber());
        assertNotNull(result.getPullRequest().getHead());
        assertEquals("feature/progress-review", result.getPullRequest().getHead().getRef());
        assertEquals("abc123", result.getPullRequest().getHead().getSha());
        assertNotNull(result.getPullRequest().getBase());
        assertEquals("main", result.getPullRequest().getBase().getRef());
    }

    @Test
    void translateNotePayload_issueNote_mapsIssueFields() {
        Map<String, Object> noteAttrs = new HashMap<>();
        noteAttrs.put("id", 55);
        noteAttrs.put("note", "@bot implement this");
        noteAttrs.put("noteable_type", "Issue");
        noteAttrs.put("author", Map.of("username", "devuser"));

        Map<String, Object> issue = new HashMap<>();
        issue.put("iid", 5);
        issue.put("title", "Add feature X");
        issue.put("description", "Please add X");

        Map<String, Object> project = new HashMap<>();
        project.put("id", 1);
        project.put("name", "testrepo");
        project.put("path_with_namespace", "testowner/testrepo");
        project.put("namespace", Map.of("path", "testowner"));

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", noteAttrs);
        gitlabPayload.put("issue", issue);
        gitlabPayload.put("project", project);
        gitlabPayload.put("user", Map.of("username", "devuser"));

        WebhookPayload result = handler.translateNotePayload(gitlabPayload, noteAttrs);

        assertNotNull(result.getIssue());
        assertEquals(5L, result.getIssue().getNumber());
        assertEquals("Add feature X", result.getIssue().getTitle());
        assertEquals("Please add X", result.getIssue().getBody());
        assertNull(result.getPullRequest());
    }

    @Test
    void translateIssuePayload_mapsAllFields_withStringNamespace() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("iid", 1);
        attrs.put("title", "Create a Java project");
        attrs.put("description", "Create a Maven project with a main class");

        // GitLab Issue Hook sends namespace as a plain String
        Map<String, Object> project = new HashMap<>();
        project.put("id", 1);
        project.put("name", "my-cool-project");
        project.put("path_with_namespace", "java/my-cool-project");
        project.put("namespace", "java");

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", project);
        gitlabPayload.put("user", Map.of("username", "root"));
        gitlabPayload.put("assignees", List.of(
                Map.of("id", 2, "username", "ai_bot", "name", "Ai")
        ));

        WebhookPayload result = handler.translateIssuePayload(gitlabPayload, attrs);

        assertNotNull(result.getIssue());
        assertEquals(1L, result.getIssue().getNumber());
        assertEquals("Create a Java project", result.getIssue().getTitle());
        assertEquals("Create a Maven project with a main class", result.getIssue().getBody());
        assertNotNull(result.getIssue().getAssignee());
        assertEquals("ai_bot", result.getIssue().getAssignee().getLogin());
        assertNotNull(result.getIssue().getAssignees());
        assertEquals(1, result.getIssue().getAssignees().size());
        assertEquals("ai_bot", result.getIssue().getAssignees().getFirst().getLogin());

        assertNotNull(result.getRepository());
        assertEquals("my-cool-project", result.getRepository().getName());
        assertEquals("java/my-cool-project", result.getRepository().getFullName());
        assertEquals("java", result.getRepository().getOwner().getLogin());

        assertNotNull(result.getSender());
        assertEquals("root", result.getSender().getLogin());

        assertNull(result.getPullRequest());
    }

    @Test
    void translateIssuePayload_mapsAllFields_withMapNamespace() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("iid", 1);
        attrs.put("title", "Create a Java project");
        attrs.put("description", "Create a Maven project with a main class");

        // Some GitLab versions may send namespace as a Map
        Map<String, Object> project = new HashMap<>();
        project.put("id", 1);
        project.put("name", "my-cool-project");
        project.put("path_with_namespace", "java/my-cool-project");
        project.put("namespace", Map.of("path", "java"));

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", project);
        gitlabPayload.put("user", Map.of("username", "root"));
        gitlabPayload.put("assignees", List.of(
                Map.of("id", 2, "username", "ai_bot", "name", "Ai")
        ));

        WebhookPayload result = handler.translateIssuePayload(gitlabPayload, attrs);

        assertNotNull(result.getRepository());
        assertEquals("java", result.getRepository().getOwner().getLogin());
    }

    @Test
    void translateIssuePayload_noAssignees_assigneeIsNull() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("iid", 2);
        attrs.put("title", "Some issue");
        attrs.put("description", "Description");

        Map<String, Object> gitlabPayload = new HashMap<>();
        gitlabPayload.put("object_attributes", attrs);
        gitlabPayload.put("project", Map.of("id", 1, "name", "repo",
                "path_with_namespace", "owner/repo",
                "namespace", Map.of("path", "owner")));
        gitlabPayload.put("user", Map.of("username", "devuser"));

        WebhookPayload result = handler.translateIssuePayload(gitlabPayload, attrs);

        assertNotNull(result.getIssue());
        assertEquals(2L, result.getIssue().getNumber());
        assertNull(result.getIssue().getAssignee());
    }
}
