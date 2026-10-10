package org.remus.giteabot.gitlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

/**
 * API response model for an entry of a GitLab repository tree.
 * Returned by GET /api/v4/projects/{id}/repository/tree?recursive=true
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabTreeEntry {

    private String path;

    /** {@code blob}, {@code tree} or {@code commit} (submodule). */
    private String type;

    public RepositoryTreeEntry toRepositoryTreeEntry() {
        return new RepositoryTreeEntry(path, RepositoryTreeEntry.Type.fromGitObjectType(type));
    }
}
