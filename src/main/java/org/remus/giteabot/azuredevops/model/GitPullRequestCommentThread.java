package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#gitpullrequestcommentthread
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitPullRequestCommentThread {
    private Long id;
    private List<Comment> comments;
    /** A status this client does not know reads as {@link CommentThreadStatus#UNKNOWN} instead of failing the whole thread list. */
    @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
    private CommentThreadStatus status;
    private CommentThreadContext threadContext;

    // https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#commentthreadstatus
    public enum CommentThreadStatus {
        @JsonEnumDefaultValue @JsonProperty("unknown") UNKNOWN,
        /** Thread status an author has to resolve; counts against the comment-resolution policy. */
        @JsonProperty("active") ACTIVE,
        @JsonProperty("fixed") FIXED,
        @JsonProperty("wontFix") WONT_FIX,
        /** Thread status for informational comments that should never block completing the PR. */
        @JsonProperty("closed") CLOSED,
        @JsonProperty("byDesign") BY_DESIGN,
        @JsonProperty("pending") PENDING
    }
}
