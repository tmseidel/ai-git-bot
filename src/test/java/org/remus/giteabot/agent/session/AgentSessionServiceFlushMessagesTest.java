package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.session.ConversationMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies the Phase 2 batch-flush contract of
 * {@link AgentSessionService#flushMessages}: all of a round's messages and the
 * cumulative token counts are written to the managed entity, and the messages
 * carry strictly-increasing, call-ordered timestamps so the unordered
 * {@code messages} Set replays in the order they were produced.
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServiceFlushMessagesTest {

    @Mock private AgentSessionRepository repository;

    @Test
    void flushMessages_appendsBatchWithCallOrderedTimestampsAndTokens() {
        AgentSession managed = new AgentSession("o", "r", 1L, "title");
        managed.setId(7L);
        when(repository.getReferenceById(7L)).thenReturn(managed);

        AgentSessionService svc = new AgentSessionService(repository);

        List<PendingMessage> batch = List.of(
                new PendingMessage("user", "kickoff"),
                new PendingMessage("assistant", "first-ai"),
                new PendingMessage("tool", "[call_1] result"),
                new PendingMessage("user", "follow-up"));

        AgentSession result = svc.flushMessages(7L, batch, 1234L, 567L);

        // Returns the managed entity.
        assertThat(result).isSameAs(managed);

        // Token counts persisted on the managed entity.
        assertThat(managed.getTotalInputTokens()).isEqualTo(1234L);
        assertThat(managed.getTotalOutputTokens()).isEqualTo(567L);

        // All four messages were appended.
        assertThat(managed.getMessages()).hasSize(4);

        // Sorting by createdAt (how toAiMessages replays) yields call order, and
        // the timestamps are strictly increasing so the order is unambiguous.
        List<ConversationMessage> sorted = managed.getMessages().stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
        assertThat(sorted).extracting(ConversationMessage::getContent)
                .containsExactly("kickoff", "first-ai", "[call_1] result", "follow-up");
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).getCreatedAt())
                    .isAfter(sorted.get(i - 1).getCreatedAt());
        }

        // The service's replay view drops the tool message and preserves order.
        assertThat(svc.toAiMessages(managed)).extracting(AiMessage::getContent)
                .containsExactly("kickoff", "first-ai", "follow-up");
    }
}
