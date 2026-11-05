package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#comment
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Comment {
    private Long id;
    /** A type this client does not know reads as {@link CommentType#UNKNOWN} instead of failing the whole thread list. */
    @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
    private CommentType commentType;
    private String content;
    private Long parentCommentId;
    private IdentityRef author;

    public enum CommentType {
        @JsonEnumDefaultValue @JsonProperty("unknown") UNKNOWN,
        @JsonProperty("text") TEXT,
        @JsonProperty("codeChange") CODE_CHANGE,
        @JsonProperty("system") SYSTEM
    }
}
