package org.remus.giteabot.repository;

/**
 * Thrown by {@link RepositoryApiClient#getAuthenticatedUser(String)} when the provider can only
 * identify the token inside an owner (an Azure DevOps organization), but none was given and the
 * integration URL names none.
 */
public class OwnerRequiredException extends IllegalStateException {

    public OwnerRequiredException(String message) {
        super(message);
    }
}
