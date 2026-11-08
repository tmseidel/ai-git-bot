package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.RepositoryUser;

/**
 * API response model for the user a Gitea token authenticates as.
 * Returned by GET /api/v1/user
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaUser {

    private Long id;

    private String login;

    @JsonProperty("full_name")
    private String fullName;

    private String email;

    public RepositoryUser toGitUser() {
        return new RepositoryUser(id.toString(), login, fullName, email);
    }
}
