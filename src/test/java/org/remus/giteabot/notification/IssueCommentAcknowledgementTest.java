package org.remus.giteabot.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueCommentAcknowledgementTest {

    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private RepositoryApiClient repositoryApiClient;

    private IssueCommentAcknowledgement acknowledgement;
    private Bot bot;

    @BeforeEach
    void setUp() {
        acknowledgement = new IssueCommentAcknowledgement(giteaClientFactory);
        bot = new Bot();
        bot.setName("test-bot");
        bot.setGitIntegration(new GitIntegration());
    }

    @Test
    void acknowledge_addsEyesReactionToTheTriggeringComment() {
        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);

        acknowledgement.acknowledge(bot, commentPayload());

        verify(repositoryApiClient).addReaction("Test", "my-repo", 77L, "eyes");
    }

    @Test
    void acknowledge_reactionFailure_isSwallowed() {
        when(giteaClientFactory.getApiClient(any())).thenReturn(repositoryApiClient);
        doThrow(new RuntimeException("reaction api down"))
                .when(repositoryApiClient).addReaction(any(), any(), any(), any());

        assertDoesNotThrow(() -> acknowledgement.acknowledge(bot, commentPayload()));
    }

    @Test
    void acknowledge_payloadWithoutComment_isIgnored() {
        WebhookPayload payload = commentPayload();
        payload.setComment(null);

        acknowledgement.acknowledge(bot, payload);

        verifyNoInteractions(giteaClientFactory);
    }

    private static WebhookPayload commentPayload() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("my-repo");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("Test");
        repository.setOwner(owner);
        payload.setRepository(repository);
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(77L);
        payload.setComment(comment);
        return payload;
    }
}
