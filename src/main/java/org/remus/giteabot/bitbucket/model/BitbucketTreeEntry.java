package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

import java.util.List;

/**
 * API response model for an entry of a Bitbucket Cloud source listing.
 * Returned (paginated, see {@link Page}) by
 * GET /repositories/{workspace}/{repo}/src/{ref}/?max_depth=...
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketTreeEntry {

    private String path;

    /** {@code commit_file}, {@code commit_directory} or {@code commit_link} (submodule). */
    private String type;

    public RepositoryTreeEntry toRepositoryTreeEntry() {
        RepositoryTreeEntry.Type mapped = switch (type == null ? "" : type) {
            case "commit_file" -> RepositoryTreeEntry.Type.FILE;
            case "commit_directory" -> RepositoryTreeEntry.Type.DIRECTORY;
            default -> RepositoryTreeEntry.Type.OTHER;
        };
        return new RepositoryTreeEntry(path, mapped);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private List<BitbucketTreeEntry> values;
    }
}
