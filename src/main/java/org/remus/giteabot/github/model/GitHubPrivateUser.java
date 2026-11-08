package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryUser;

/**
 * API response model for the user a GitHub token authenticates as.
 * Returned by GET /user
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubPrivateUser {

    private Long id;

    private String login;

    private String name;

    /** Only the address the account made public in its profile. */
    private String email;

    /**
     * @param commitEmail the address to commit with, or {@code null} for
     *                    {@link RepositoryUser#FALLBACK_EMAIL}
     */
    public RepositoryUser toGitUser(String commitEmail) {
        return new RepositoryUser(id == null ? null : id.toString(), login, name, commitEmail);
    }
}
