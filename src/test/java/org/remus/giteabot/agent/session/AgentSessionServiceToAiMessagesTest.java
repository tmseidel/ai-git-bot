package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: when a follow-up comment arrives after a previously aborted /
 * tool-heavy run, the in-DB conversation history contains {@code role:"tool"}
 * messages and (for native function-calling providers) assistant turns whose
 * only payload was {@code tool_calls}. Replaying those naively to OpenAI /
 * Anthropic fails with
 * <em>"messages with role 'tool' must be a response to a preceeding message
 * with 'tool_calls'"</em> when the tool rows disagree with the payload the
 * preceding assistant turn announced. Rows that cannot be paired — persisted
 * before the payload columns existed, orphaned, or answered twice — are stripped
 * when rebuilding the AI history, while a complete persisted pair is replayed.
 *
 * <p>A pair is only replayed <em>complete</em>: an assistant turn whose announced
 * calls were never answered (the writer's give-up branch, or any strategy that
 * finishes on a tool-call turn) loses its payload, because OpenAI and Anthropic
 * reject an assistant {@code tool_calls} message without the matching tool results.
 * Replayed ids are rewritten to the alphabet every provider accepts, and the
 * {@code "[<id>] "} review marker the loop stores with a tool row is stripped.</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServiceToAiMessagesTest {

    @Mock private AgentSessionRepository repository;

    @Test
    void toAiMessages_dropsToolRoleAndBlankAssistantMessages() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addMessageAt(session, "assistant", "", 2); // tool-call-only turn (tool_calls lost on persistence)
        addMessageAt(session, "tool", "[call_123] some result", 3); // orphaned tool result
        addMessageAt(session, "assistant", "Done with first round.", 4);
        addMessageAt(session, "user", "Please continue", 5);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "user");
        assertThat(messages).extracting(AiMessage::getContent)
                .containsExactly("Implement feature X", "Done with first round.", "Please continue");
    }

    @Test
    void toAiMessages_keepsNormalConversation() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Hello", 1);
        addMessageAt(session, "assistant", "Hi", 2);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
    }

    @Test
    void toAiMessages_replaysAPersistedToolExchange() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("call_123", "cat"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_123] file body", null, "call_123", 3);
        addMessageAt(session, "assistant", "Done with first round.", 4);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(messages.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("call_123");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_123");
        assertThat(messages.get(2).getToolResult()).isEqualTo("file body");
    }

    @Test
    void toAiMessages_dropsToolRowsThatAnswerNoAnnouncedCall() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addPayloadMessageAt(session, "assistant", "reading", toolCallsJson("call_1", "cat"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_1] first", null, "call_1", 3);
        addPayloadMessageAt(session, "tool", "[call_1] answered twice", null, "call_1", 4);
        addPayloadMessageAt(session, "tool", "[call_2] never announced", null, "call_2", 5);
        addPayloadMessageAt(session, "tool", "[call_1] id without a payload turn", null, null, 6);
        addMessageAt(session, "user", "Please continue", 7);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "user");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(messages.get(2).getToolResult()).isEqualTo("first");
    }

    @Test
    void toAiMessages_dropsAnAssistantTurnWhoseCallsWereNeverAnswered() {
        // The wrap-up-then-give-up path: the run finishes on a tool-call turn, so the
        // session holds a payload no tool row answers. Replaying it as-is makes the
        // follow-up run fail with "tool_use ids were found without tool_result blocks".
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Tighten this issue", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("call_9", "rg"), null, 2);
        addMessageAt(session, "user", "Please continue", 3);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole).containsExactly("user", "user");
        assertThat(messages).allSatisfy(message -> assertThat(message.getToolCalls()).isNullOrEmpty());
    }

    @Test
    void toAiMessages_replaysAPartlyAnsweredTurnAsPlainContent() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Tighten this issue", 1);
        addPayloadMessageAt(session, "assistant", "reading the module",
                toolCallsJsonForIds("call_1", "call_2"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_1] first", null, "call_1", 3);
        addMessageAt(session, "user", "Please continue", 4);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole).containsExactly("user", "assistant", "user");
        assertThat(messages.get(1).getContent()).isEqualTo("reading the module");
        assertThat(messages.get(1).getToolCalls()).isNullOrEmpty();
    }

    @Test
    void toAiMessages_replaysTheAnnouncedIdsInCallOrderOnceEach() {
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Tighten this issue", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJsonForIds("call_a", "call_b"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_b] second", null, "call_b", 3);
        addPayloadMessageAt(session, "tool", "[call_a] first", null, "call_a", 4);
        addPayloadMessageAt(session, "tool", "[call_a] duplicate", null, "call_a", 5);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "tool");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_a");
        assertThat(messages.get(2).getToolResult()).isEqualTo("first");
        assertThat(messages.get(3).getToolCallId()).isEqualTo("call_b");
        assertThat(messages.get(3).getToolResult()).isEqualTo("second");
    }

    @Test
    void toAiMessages_rewritesIdsToTheAlphabetEveryProviderAccepts() {
        // A local Ollama integration hands out ids like "cat:0"; Anthropic requires
        // ^[a-zA-Z0-9_-]+$ and rejects the request outright. The session can outlive the
        // integration that wrote it, so the pair is rewritten on both sides.
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Tighten this issue", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("cat:0", "cat"), null, 2);
        addPayloadMessageAt(session, "tool", "[cat:0] file body", null, "cat:0", 3);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("cat_0");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("cat_0");
        assertThat(messages.get(2).getToolResult()).isEqualTo("file body");
    }

    @Test
    void toAiMessages_replaysACodingAgentFollowUpWithItsEarlierToolExchange() {
        // Replay is shared, so the coding agent gets its earlier tool output back too —
        // it used to be stripped for every agent. The shape is the one the provider
        // needs; the size is whatever the run persisted, already capped by
        // truncateToolMessages while the run was in flight.
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Implement feature X", 1);
        addPayloadMessageAt(session, "assistant", "inspecting the module",
                toolCallsJsonForIds("call_1", "call_2"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_1] " + "x".repeat(500), null, "call_1", 3);
        addPayloadMessageAt(session, "tool", "[call_2] two matches", null, "call_2", 4);
        addMessageAt(session, "assistant", "Done with first round.", 5);
        addMessageAt(session, "user", "Please continue", 6);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool", "tool", "assistant", "user");
        assertThat(messages.get(1).getToolCalls()).extracting(ToolCall::id)
                .containsExactly("call_1", "call_2");
        assertThat(messages.get(2).getToolResult()).isEqualTo("x".repeat(500));
        assertThat(messages.get(3).getToolResult()).isEqualTo("two matches");
    }

    @Test
    void toAiMessages_replaysThePairARunWroteWhenItFinishedOnAToolCallRound() {
        // The shape a run leaves behind when it executes tools and then stops: the loop
        // writes the assistant turn and its results in one batch, so the session *ends*
        // on the tool row. Replaying it must keep the pair — a turn replayed as prose is
        // an announcement without an outcome, and the follow-up run repeats the edit.
        AgentSessionService svc = new AgentSessionService(repository);
        AgentSession session = new AgentSession("o", "r", 1L, "title");
        addMessageAt(session, "user", "Add the row-filter checkbox", 1);
        addPayloadMessageAt(session, "assistant", "", toolCallsJson("call_7", "patch-file"), null, 2);
        addPayloadMessageAt(session, "tool", "[call_7] patched tests/e2e/features/Feature.feature (EXACT)",
                null, "call_7", 3);

        List<AiMessage> messages = svc.toAiMessages(session);

        assertThat(messages).extracting(AiMessage::getRole)
                .containsExactly("user", "assistant", "tool");
        assertThat(messages.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("call_7");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_7");
        assertThat(messages.get(2).getToolResult())
                .isEqualTo("patched tests/e2e/features/Feature.feature (EXACT)");
    }

    private static void addMessageAt(AgentSession session, String role, String content, long offsetSeconds) {
        ConversationMessage msg = new ConversationMessage(role, content);
        msg.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L + offsetSeconds));
        session.getMessages().add(msg);
    }

    private static void addPayloadMessageAt(AgentSession session, String role, String content,
                                           String toolCalls, String toolCallId, long offsetSeconds) {
        ConversationMessage msg = new ConversationMessage(role, content);
        msg.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L + offsetSeconds));
        msg.setToolCalls(toolCalls);
        msg.setToolCallId(toolCallId);
        session.getMessages().add(msg);
    }

    /** Serialises a payload announcing one call per given id. */
    private static String toolCallsJsonForIds(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new ToolCall(id, "cat", AgentJackson.mapper().readTree("{}"), null));
        }
        return AgentJackson.mapper().writeValueAsString(calls);
    }

    /** Serialises a one-call payload the way {@code AgentSessionService} stores it. */
    private static String toolCallsJson(String callId, String toolName) {
        return AgentJackson.mapper().writeValueAsString(
                List.of(new ToolCall(callId, toolName, AgentJackson.mapper().readTree("{}"), null)));
    }
}



