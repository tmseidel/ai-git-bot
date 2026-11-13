package org.remus.giteabot.gitlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryUser;

/**
 * API response model for the user a GitLab token authenticates as.
 * Returned by GET /api/v4/user
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabUserPublic {

    private Long id;

    private String username;

    private String name;

    /** The account's primary e-mail address, returned to the user itself and to administrators. */
    private String email;

    /** Returned to the user itself, which the token always is here. */
    @JsonProperty("commit_email")
    private String commitEmail;

    public RepositoryUser toGitUser() {
        return new RepositoryUser(id.toString(), username, name,
                commitEmail != null ? commitEmail : email);
    }
}
