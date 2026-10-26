package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * Azure DevOps implementation of {@link ReviewComment}, flattened from a comment inside a
 * pull-request thread. {@code path} and {@code line} come from the enclosing thread's
 * {@code threadContext}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureDevopsReviewComment implements ReviewComment {

    private Long id;

    private String body;

    private String path;

    private Integer line;

    private String userLogin;

    /** Azure DevOps does not send surrounding diff context with a thread. */
    @Override
    public String getDiffHunk() {
        return null;
    }
}
