package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.GitUser;

/**
 * API response model for the user a GitHub token authenticates as.
 * Returned by GET /user
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubAuthenticatedUser {

    private Long id;

    private String login;

    private String name;

    /** Only the address the account made public in its profile. */
    private String email;

    public GitUser toGitUser() {
        return new GitUser(id == null ? null : id.toString(), login, name, email);
    }
}
