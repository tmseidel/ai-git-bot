package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

import java.util.List;

/**
 * API response model for a recursive GitHub git tree.
 * Returned by GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubTree {

    private List<Entry> tree;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private String path;

        /** {@code blob}, {@code tree} or {@code commit} (submodule). */
        private String type;

        public RepositoryTreeEntry toRepositoryTreeEntry() {
            return new RepositoryTreeEntry(path, RepositoryTreeEntry.Type.fromGitObjectType(type));
        }
    }
}
