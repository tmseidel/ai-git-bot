package org.remus.giteabot.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentErrorNotificationServiceTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    @Test
    void postInternalErrorComment_sanitizesErrorMessage() {
        AgentErrorNotificationService service = new AgentErrorNotificationService(repositoryClient);

        service.postInternalErrorComment("owner", "repo", 42L,
                "AI Agent", "Please try again.",
                new RuntimeException("bad `message`\nwith details\rhere"));

        verify(repositoryClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("I hit an internal error while processing this request: `bad 'message' with details here`"));
    }

    @Test
    void postInternalErrorComment_usesFallbackForMissingMessage() {
        AgentErrorNotificationService service = new AgentErrorNotificationService(repositoryClient);

        service.postInternalErrorComment("owner", "repo", 42L,
                "AI Technical Writer", "Please try again.",
                new RuntimeException((String) null));

        verify(repositoryClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("`unknown internal error`"));
    }
}

