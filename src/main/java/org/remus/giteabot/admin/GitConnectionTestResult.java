package org.remus.giteabot.admin;

import org.remus.giteabot.repository.model.RepositoryUser;

import java.util.List;

/**
 * Outcome of {@link GitConnectionTestService#test testing} a Git integration's connection.
 *
 * @param messageKey  the message bundle key summarizing the outcome
 * @param messageArgs the arguments of {@code messageKey}
 * @param detail      technical detail for a failure (HTTP status and response body, or the
 *                    root cause of a connection error), or {@code null}
 * @param user        the account the token authenticates as, set only on success
 */
public record GitConnectionTestResult(String messageKey, List<Object> messageArgs, String detail,
                                      RepositoryUser user) {

    /** Message key of a result asking for an Azure DevOps organization the URL does not name. */
    static final String ORGANIZATION_REQUIRED = "test.git.organizationRequired";

    static GitConnectionTestResult success(RepositoryUser user) {
        return new GitConnectionTestResult("test.git.success", List.of(), null, user);
    }

    static GitConnectionTestResult failure(String messageKey, String detail, Object... messageArgs) {
        return new GitConnectionTestResult(messageKey, List.of(messageArgs), detail, null);
    }

    /** Whether the provider identified the token's account. */
    public boolean success() {
        return user != null;
    }

    /**
     * Whether the test needs an Azure DevOps organization the URL does not name, so the form
     * should ask for one and test again.
     */
    public boolean organizationRequired() {
        return ORGANIZATION_REQUIRED.equals(messageKey);
    }
}
