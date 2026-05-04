package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookPayload {

    private String action;

    private Long number;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;

    private Comment comment;

    private Issue issue;

    private Review review;

    private Owner sender;

    @JsonProperty("requested_reviewer")
    private Owner requestedReviewer;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        private Long id;
        private Long number;
        private String title;
        private String body;
        private String state;
        private Boolean merged;

        @JsonProperty("diff_url")
        private String diffUrl;

        private Head head;
        private Head base;

        private Owner user;

        @JsonProperty("requested_reviewers")
        private java.util.List<Owner> requestedReviewers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Head {
        private String ref;
        private String sha;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private Long id;
        private String name;

        @JsonProperty("full_name")
        private String fullName;

        private Owner owner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Owner {
        private String login;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Comment {
        private Long id;
        private String body;
        private Owner user;

        // Review comment specific fields (inline comments on code)
        private String path;

        @JsonProperty("diff_hunk")
        private String diffHunk;

        private Integer line;

        @JsonProperty("pull_request_review_id")
        private Long pullRequestReviewId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue {
        private Long number;
        private String title;
        private String body;

        /**
         * The branch reference associated with this issue (if set in Gitea).
         * When set, the agent will use this branch as the base for the PR.
         */
        private String ref;

        @JsonProperty("pull_request")
        private IssuePullRequest pullRequest;

        private Owner assignee;

        private java.util.List<Owner> assignees;

        private Owner user;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssuePullRequest {
        private Boolean merged;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Review {
        private Long id;
        private String type;
        private String content;
    }
}
