package org.remus.giteabot.event.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.repository.model.CommentId;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class WebhookPayload {

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
        /**
         * The provider's stable account id, the same as {@code RepositoryUser#id} for the same
         * account (Bitbucket: {@code account_id}; Azure DevOps: the identity GUID). Unlike the
         * login it cannot be changed by the user, so the bot is recognised by it.
         * {@code null} when the payload carries none.
         */
        private String id;
        private String login;
        /** Reviewer vote (Azure DevOps only); {@code null} when the payload carries none. */
        private Integer vote;

        /**
         * An owner from a payload's user object.
         *
         * @param id    the user's id as the payload carries it: a JSON number or string;
         *              anything else, or a blank string, counts as no id
         * @param login the user's login
         */
        public static Owner of(Object id, String login) {
            Owner owner = new Owner();
            owner.setId(id instanceof Number || id instanceof String s && !s.isBlank() ? id.toString() : null);
            owner.setLogin(login);
            return owner;
        }
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
