package org.remus.giteabot.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiMessage;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private ReviewSessionRepository repository;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void getOrCreateSession_createsNewSession() {
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.empty());
        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSession session = sessionService.getOrCreateSession("owner", "repo", 1L, "default");

        assertEquals("owner", session.getRepoOwner());
        assertEquals("repo", session.getRepoName());
        assertEquals(1L, session.getPrNumber());
        assertEquals("default", session.getPromptName());
        verify(repository).save(any(ReviewSession.class));
    }

    @Test
    void getOrCreateSession_reusesExistingSession() {
        ReviewSession existing = new ReviewSession("owner", "repo", 1L, "default");
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.of(existing));

        ReviewSession session = sessionService.getOrCreateSession("owner", "repo", 1L, "default");

        assertSame(existing, session);
        verify(repository, never()).save(any());
    }

    @Test
    void addMessage_appendsToSession() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sessionService.addMessage(session, "user", "Hello");

        assertEquals(1, session.getMessages().size());
        assertEquals("user", session.getMessages().getFirst().getRole());
        assertEquals("Hello", session.getMessages().getFirst().getContent());
        verify(repository).save(session);
    }

    @Test
    void rememberParticipant_addsNewParticipant() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sessionService.rememberParticipant(session, "Alice");

        assertTrue(session.hasParticipant("alice"));
        verify(repository).save(session);
    }

    @Test
    void rememberParticipant_existingParticipant_skipsSave() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addParticipant("alice");

        sessionService.rememberParticipant(session, "Alice");

        verify(repository, never()).save(any());
    }

    @Test
    void hasParticipant_checksNormalizedLogin() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addParticipant("alice");

        assertTrue(sessionService.hasParticipant(session, "ALICE"));
        assertFalse(sessionService.hasParticipant(session, "bob"));
    }

    @Test
    void deleteSession_deletesFromRepository() {
        sessionService.deleteSession("owner", "repo", 1L);

        verify(repository).deleteByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L);
    }

    @Test
    void toAiMessages_convertsCorrectly() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addMessage("user", "Review this PR");
        session.addMessage("assistant", "Looks good!");

        List<AiMessage> messages = sessionService.toAiMessages(session);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Review this PR", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Looks good!", messages.get(1).getContent());
    }

    @Test
    void getSession_returnsOptional() {
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.empty());

        Optional<ReviewSession> result = sessionService.getSession("owner", "repo", 1L);

        assertTrue(result.isEmpty());
    }

    @Test
    void compactContextWindow_belowThreshold_noCompaction() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addMessage("user", "Short message");
        session.addMessage("assistant", "Short reply");

        ReviewSession result = sessionService.compactContextWindow(session);

        assertEquals(2, result.getMessages().size());
        verify(repository, never()).save(any());
    }

    @Test
    void compactContextWindow_fewMessages_noCompaction() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addMessage("user", "Message 1");
        session.addMessage("assistant", "Reply 1");
        session.addMessage("user", "Message 2");
        session.addMessage("assistant", "Reply 2");

        ReviewSession result = sessionService.compactContextWindow(session);

        assertEquals(4, result.getMessages().size());
        verify(repository, never()).save(any());
    }

    @Test
    void compactContextWindow_manyMessagesButSmallContent_noCompaction() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        for (int i = 0; i < 10; i++) {
            session.addMessage("user", "Question " + i);
            session.addMessage("assistant", "Answer " + i);
        }

        ReviewSession result = sessionService.compactContextWindow(session);

        assertEquals(20, result.getMessages().size());
        verify(repository, never()).save(any());
    }

    @Test
    void compactContextWindow_largeContent_compacts() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        String largeDiff = "x".repeat(40000);
        session.addMessage("user", "Review this PR\n" + largeDiff);
        session.addMessage("assistant", "Here's my review: " + "y".repeat(15000));
        session.addMessage("user", "What about security?");
        session.addMessage("assistant", "Security looks fine");
        session.addMessage("user", "Thanks!");
        session.addMessage("assistant", "You're welcome!");

        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSession result = sessionService.compactContextWindow(session);

        assertTrue(result.getMessages().size() <= 6);
        assertTrue(result.getMessages().getLast().getContent().contains("welcome"));
        verify(repository).save(session);
    }

    @Test
    void compactContextWindow_preservesRecentMessages() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        String largeDiff = "x".repeat(60000);
        session.addMessage("user", largeDiff);
        session.addMessage("assistant", "Review complete");
        session.addMessage("user", "Question 1");
        session.addMessage("assistant", "Answer 1");
        session.addMessage("user", "Question 2");
        session.addMessage("assistant", "Answer 2");
        session.addMessage("user", "Final question");
        session.addMessage("assistant", "Final answer");

        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSession result = sessionService.compactContextWindow(session);

        List<ConversationMessage> messages = result.getMessages();
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("Final answer")));
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("Final question")));
        verify(repository).save(session);
    }
}
