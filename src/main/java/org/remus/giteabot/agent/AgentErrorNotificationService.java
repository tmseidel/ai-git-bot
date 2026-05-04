package org.remus.giteabot.agent;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;

@Slf4j
public class AgentErrorNotificationService {

    private final RepositoryApiClient repositoryClient;

    public AgentErrorNotificationService(RepositoryApiClient repositoryClient) {
        this.repositoryClient = repositoryClient;
    }

    public void postInternalErrorComment(String owner, String repo, Long issueNumber,
                                         String agentDisplayName, String retryGuidance,
                                         Exception exception) {
        try {
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "⚠️ **" + agentDisplayName + "**: I hit an internal error while processing this request: `"
                            + sanitizeErrorMessage(exception) + "`\n\n"
                            + retryGuidance);
        } catch (Exception commentException) {
            log.error("Failed to post internal error comment on issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, commentException.getMessage(), commentException);
        }
    }

    String sanitizeErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "unknown internal error";
        }
        return exception.getMessage()
                .replace('`', '\'')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .strip();
    }
}

