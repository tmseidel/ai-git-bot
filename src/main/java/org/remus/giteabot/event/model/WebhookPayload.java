package org.remus.giteabot.event.model;

import lombok.Data;
import org.remus.giteabot.repository.model.CommentId;

import java.util.List;

@Data
public class WebhookPayload {

    private String action;

    private Long number;

    private PullRequest pullRequest;

    private Repository repository;

    private Comment comment;

    private Issue issue;

    private Review review;

    private Owner sender;

    private Owner requestedReviewer;

    @Data
    public static class PullRequest {
        private Long id;
        private Long number;
        private String title;
        private String body;
        private String state;
        private Boolean merged;

        private String diffUrl;

        private Head head;
        private Head base;

        private Owner user;

        private Owner assignee;

        private List<Owner> assignees;

        private List<Owner> requestedReviewers;
    }

    @Data
    public static class Head {
        private String ref;
        private String sha;
    }

    @Data
    public static class Repository {
        private Long id;
        private String name;

        private String fullName;

        private Owner owner;
    }

    @Data
    public static class Owner {
        private String login;
        /**
         * Stable account id (Gitea, GitHub, GitLab: the numeric user id; Azure DevOps: the identity
         * GUID); {@code null} when the payload carries none.
         */
        private String id;
        /** Reviewer vote (Azure DevOps only); {@code null} when the payload carries none. */
        private Integer vote;
    }

    @Data
    public static class Comment {
        private CommentId id;
        private String body;
        private Owner user;

        // Review comment specific fields (inline comments on code)
        private String path;

        private String diffHunk;

        private Integer line;

        private Long pullRequestReviewId;
    }

    @Data
    public static class Issue {
        private Long number;
        private String title;
        private String body;

        /**
         * The branch reference associated with this issue (if set in Gitea).
         * When set, the agent will use this branch as the base for the PR.
         */
        private String ref;

        private IssuePullRequest pullRequest;

        private Owner assignee;

        private List<Owner> assignees;

        private Owner user;
    }

    @Data
    public static class IssuePullRequest {
        private Boolean merged;
    }

    @Data
    public static class Review {
        private Long id;
        private String type;
        private String content;
    }
}
