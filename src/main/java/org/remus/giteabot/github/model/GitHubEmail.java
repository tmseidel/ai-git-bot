package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Locale;

/**
 * API response model for an e-mail address of the account a GitHub token authenticates as.
 * Returned (as a list) by GET /user/emails
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubEmail {

    private String email;

    private boolean primary;

    private boolean verified;

    /** Whether this is a GitHub-provided {@code noreply} address ({@code …@users.noreply.<host>}). */
    public boolean isNoreply() {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        return at > 0 && email.substring(at + 1).toLowerCase(Locale.ROOT).startsWith("users.noreply.");
    }
}
