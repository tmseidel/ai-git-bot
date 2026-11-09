package org.remus.giteabot.agent.session;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end persistence test for the AgentSession compaction + batch-flush
 * lifecycle, exercised across real (committing) transaction boundaries so the
 * entities detach between steps — exactly the webhook flow's behaviour.
 *
 * <p>Reproduces the sequence behind the {@code ObjectNotFoundException} fixed in
 * commit 71632d7: a follow-up run compacts a session whose history was already
 * trimmed by a previous compaction, then flushes new messages. Because every
 * write now goes through a freshly-fetched managed entity (never a {@code merge}
 * of the caller's detached object), the deleted rows are never re-resolved and
 * the cycle completes cleanly.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentSessionCompactionIntegrationTest {

    @Autowired private AgentSessionRepository repository;
    @Autowired private AgentSessionService service;
    @Autowired private TransactionTemplate tx;

    private List<PendingMessage> bigBatch(int count) {
        String big = "x".repeat(10_000); // 10k chars each -> 10 messages exceed the 80k threshold
        List<PendingMessage> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new PendingMessage(i % 2 == 0 ? "user" : "assistant", big));
        }
        return batch;
    }

    @Test
    void compactFlushCompactCycle_acrossTransactions_doesNotThrowAndKeepsHistoryBounded() {
        // tx1 — create the session (committed, then detached).
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_001L, "Big session").getId());
        assertThat(id).isNotNull();

        // tx2 — first run persists a large history (>8 messages, >80k chars).
        tx.executeWithoutResult(s -> service.flushMessages(id, bigBatch(10), 1000L, 500L));

        // tx3 — follow-up run compacts: 8 most-recent retained + 1 summary message.
        AgentSession compacted = tx.execute(s -> service.compactContextWindow(id));
        assertThat(compacted.getMessages()).hasSize(9);

        // tx4 — that run flushes another large batch onto the compacted session.
        tx.executeWithoutResult(s -> service.flushMessages(id, bigBatch(10), 2000L, 900L));

        // tx5 — a third run compacts a session whose history was ALREADY trimmed
        // once. This is the step that previously threw ObjectNotFoundException
        // (merging a detached entity whose collection referenced deleted rows).
        AgentSession recompacted = tx.execute(s -> service.compactContextWindow(id));
        assertThat(recompacted.getMessages()).hasSize(9);

        // tx6 — flush the final round's messages.
        tx.executeWithoutResult(s -> service.flushMessages(id,
                List.of(new PendingMessage("user", "follow-up"),
                        new PendingMessage("assistant", "answer")),
                2500L, 1100L));

        // tx7 — reload from a fresh transaction and assert the committed state.
        AgentSession reloaded = tx.execute(s -> {
            AgentSession sess = repository.findById(id).orElseThrow();
            sess.getMessages().size(); // force-initialise within the transaction
            return sess;
        });
        assertThat(reloaded.getMessages()).hasSize(11); // 9 after compaction + 2 new
        assertThat(reloaded.getTotalInputTokens()).isEqualTo(2500L);
        assertThat(reloaded.getTotalOutputTokens()).isEqualTo(1100L);

        // Replay view is well-formed and ordered (no exception, no deleted-row refs).
        assertThat(service.toAiMessages(reloaded)).isNotEmpty();
    }

    @Test
    void compaction_neverKeepsAToolRowWithoutTheAssistantTurnThatAnnouncedIt() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_002L, "Tool exchange session").getId());
        String big = "y".repeat(17_000); // five of these exceed the 80k compaction threshold
        ToolCall call = new ToolCall("call_1", "cat", null, null);

        // Ten messages whose 8-message window would start on the first tool row.
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", big),
                new PendingMessage("assistant", "reading",
                        new PendingMessage.ToolPayload(List.of(call), null)),
                new PendingMessage("tool", "[call_1] first body",
                        new PendingMessage.ToolPayload(null, "call_1")),
                new PendingMessage("tool", "[call_1] second body",
                        new PendingMessage.ToolPayload(null, "call_1")),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        AgentSession compacted = tx.execute(s -> service.compactContextWindow(id));

        List<ConversationMessage> kept = compacted.getMessages().stream()
                .filter(message -> !message.getContent().startsWith("[Previous agent session context"))
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt))
                .toList();
        // The compaction ran (it replaced the removed prefix with a summary) ...
        assertThat(compacted.getMessages()).anySatisfy(message ->
                assertThat(message.getContent()).contains("compacted to save space"));
        // ... and the window was widened back onto the announcing assistant turn rather
        // than starting on the tool row, which would have kept a result without its call.
        assertThat(kept.get(0).getRole()).isEqualTo("assistant");
        assertThat(kept.get(0).getToolCalls()).contains("call_1");
        assertThat(kept).extracting(ConversationMessage::getRole).contains("tool");

        List<AiMessage> replay = service.toAiMessages(compacted);
        assertThat(replay).isNotEmpty();
        // The replay opens with the compaction summary, then the retained window — which
        // has to start on the announcing assistant turn rather than on its tool row.
        assertThat(replay.get(0).getRole()).isEqualTo("user");
        assertThat(replay.get(0).getContent()).startsWith("[Previous agent session context");
        assertThat(replay.get(1).getRole()).isEqualTo("assistant");
        assertThat(replay.get(1).getToolCalls()).extracting(ToolCall::id).containsExactly("call_1");
        assertThat(replay.get(2).getRole()).isEqualTo("tool");
        assertThat(replay.get(2).getToolCallId()).isEqualTo("call_1");
    }

    @Test
    void compaction_opensTheReplayWithASummaryThatNamesTheTaskTheBranchAndTheChangedFiles() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_003L, "Add the row-filter checkbox").getId());
        tx.executeWithoutResult(s -> {
            AgentSession session = repository.findById(id).orElseThrow();
            service.setBranchName(session, "ai-agent/issue-261");
            service.recordPlan(session,
                    "patch the feature file, then run the e2e suite", "{\"summary\":\"patch\"}");
        });

        String big = "z".repeat(17_000); // several of these exceed the 80k threshold
        var patchArgs = AgentJackson.mapper().readTree(
                "{\"path\":\"tests/e2e/features/Feature.feature\",\"old\":\"a\",\"new\":\"b\"}");
        ToolCall patch = new ToolCall("call_9", "patch-file", patchArgs, null);

        // The removed prefix holds the one mutation whose outcome the follow-up run
        // must not have to rediscover from the codebase.
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", "Add the checkbox filter to the feature file\n" + big),
                new PendingMessage("assistant", "patching",
                        new PendingMessage.ToolPayload(List.of(patch), null)),
                new PendingMessage("tool", "[call_9] patched Feature.feature (EXACT)",
                        new PendingMessage.ToolPayload(null, "call_9")),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", "and cover the empty state\n" + big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", "follow up")), 0L, 0L));

        AgentSession compacted = tx.execute(s -> service.compactContextWindow(id));

        List<ConversationMessage> ordered = compacted.getMessages().stream()
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt))
                .toList();
        assertThat(ordered).hasSize(9); // 8 retained + the summary
        // Position, not just presence: the summary has to sort *before* the retained
        // window, otherwise it lands right before the new prompt as a closing note.
        assertThat(ordered.get(0).getContent()).startsWith("[Previous agent session context");

        List<AiMessage> replay = service.toAiMessages(compacted);
        assertThat(replay.get(0).getRole()).isEqualTo("user");
        assertThat(replay.get(0).getContent())
                .contains("Add the row-filter checkbox")
                .contains("ai-agent/issue-261")
                .contains("patch the feature file")
                .contains("tests/e2e/features/Feature.feature")
                .contains("Add the checkbox filter")
                .doesNotContain("re-read files or re-run tools");
    }

    @Test
    void compaction_thresholdAndRetainedWindowAreConfigurable() {
        AgentSessionService smallBudget = new AgentSessionService(repository, 20_000, 3);
        Long id = tx.execute(s -> smallBudget.createSession(
                "owner", "repo", 9_000_004L, "Small budget").getId());

        String big = "w".repeat(6_000); // 24k in total: over 20k, under the 80k default
        tx.executeWithoutResult(s -> smallBudget.flushMessages(id, List.of(
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", "follow-up")), 0L, 0L));

        // The container-wired default (80k / 8) leaves this session alone ...
        AgentSession untouched = tx.execute(s -> service.compactContextWindow(id));
        assertThat(untouched.getMessages()).hasSize(5);

        // ... while the configured service compacts it and keeps three messages.
        AgentSession compacted = tx.execute(s -> smallBudget.compactContextWindow(id));
        assertThat(compacted.getMessages()).hasSize(4);
    }

    /** The summary row a compaction left behind, in replay order. */
    private String summaryOf(AgentSession compacted) {
        return compacted.getMessages().stream()
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt))
                .map(ConversationMessage::getContent)
                .filter(content -> content != null && content.startsWith("[Previous agent session context"))
                .findFirst()
                .orElse("");
    }

    /** Arguments of a file tool call, in the shape a native provider produces. */
    private static JsonNode pathArgs(String path) {
        try {
            return AgentJackson.mapper().readTree("{\"path\":\"" + path + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void compaction_quotesTheBodyOfAFollowUpCommentInsteadOfDroppingIt() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_101L, "Add the row-filter checkbox").getId());

        // What AgentPromptBuilder.buildContinuationPrompt actually persists: the user's
        // words sit *under* a markdown heading, so skipping every turn that starts with
        // "#" dropped the one message this section exists to quote.
        String comment = "## New User Comment\n"
                + "Also cover the empty state\n\n## Current Issue Comments\n(none)";
        String big = "x".repeat(12_000);
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", comment),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big)), 0L, 0L));

        String summary = summaryOf(tx.execute(s -> service.compactContextWindow(id)));

        assertThat(summary).contains("- \"Also cover the empty state\"")
                .doesNotContain("## New User Comment")
                .doesNotContain("Current Issue Comments");
    }

    @Test
    void compaction_skipsTheScaffoldingTheLoopStoresAsUserTurns() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_102L, "Add the row-filter checkbox").getId());

        String big = "x".repeat(12_000);
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", "## Issue\n**Title**: Add the checkbox\n**Description**: x"),
                new PendingMessage("user", "## Tool Execution Results\n\nExit code: 0"),
                new PendingMessage("user", "The review is approaching its exploration or round limit. Wrap up."),
                // An opening this code does not know stays: it is far more likely a human
                // than a shape the loop started emitting.
                new PendingMessage("user", "## 需求变更：把页大小改成 20"),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        String summary = summaryOf(tx.execute(s -> service.compactContextWindow(id)));

        assertThat(summary).contains("需求变更")
                .doesNotContain("**Title**")
                .doesNotContain("Tool Execution Results")
                .doesNotContain("The review is approaching");
    }

    @Test
    void compaction_omitsAFileWhoseOwnToolRowReportedAFailure() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_103L, "Patch two files").getId());

        ToolCall applied = new ToolCall("call_ok", "patch-file", pathArgs("src/Ok.java"), null);
        ToolCall rejected = new ToolCall("call_no", "patch-file", pathArgs("src/No.java"), null);
        String big = "x".repeat(11_000);
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", "patch both files"),
                new PendingMessage("assistant", "patching both",
                        new PendingMessage.ToolPayload(List.of(applied, rejected), null)),
                new PendingMessage("tool", "[call_ok] Exit code: 0\nOutput:\n```\npatched\n```",
                        new PendingMessage.ToolPayload(null, "call_ok")),
                new PendingMessage("tool", "[call_no] Exit code: 1\nError: old text not found",
                        new PendingMessage.ToolPayload(null, "call_no")),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        String summary = summaryOf(tx.execute(s -> service.compactContextWindow(id)));

        assertThat(summary).contains("src/Ok.java").doesNotContain("src/No.java");
    }

    @Test
    void compaction_carriesTheFileListOfAPreviousSummaryForward() {
        Long id = tx.execute(s -> service.createSession(
                "owner", "repo", 9_000_104L, "Patch the feature file").getId());

        ToolCall patch = new ToolCall("call_1", "patch-file", pathArgs("src/Feature.java"), null);
        String big = "x".repeat(12_000);
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", big),
                new PendingMessage("assistant", "patching",
                        new PendingMessage.ToolPayload(List.of(patch), null)),
                new PendingMessage("tool", "[call_1] Exit code: 0\nOutput:\n```\npatched\n```",
                        new PendingMessage.ToolPayload(null, "call_1")),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        AgentSession first = tx.execute(s -> service.compactContextWindow(id));
        assertThat(summaryOf(first)).contains("src/Feature.java");

        // The summary of the first round now sits inside the window the second round
        // removes. Only it still names the file edited before the first compaction.
        tx.executeWithoutResult(s -> service.flushMessages(id, List.of(
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big)), 0L, 0L));

        AgentSession second = tx.execute(s -> service.compactContextWindow(id));
        String summary = summaryOf(second);

        assertThat(summary).contains("src/Feature.java");
        // Its own text is not something a human asked for, so it must not be quoted back.
        assertThat(summary).doesNotContain("- \"[Previous agent session context");
        assertThat(second.getMessages().stream()
                .map(ConversationMessage::getContent)
                .filter(content -> content != null && content.startsWith("[Previous agent session context"))
                .count()).isEqualTo(1);
    }

    @Test
    void compaction_namesAtMostTwentyFiles() {
        AgentSessionService smallBudget = new AgentSessionService(repository, 20_000, 3);
        Long id = tx.execute(s -> smallBudget.createSession(
                "owner", "repo", 9_000_105L, "Touch many files").getId());

        List<ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            calls.add(new ToolCall("call_" + i, "patch-file", pathArgs("src/F" + i + ".java"), null));
        }
        String big = "x".repeat(6_000);
        tx.executeWithoutResult(s -> smallBudget.flushMessages(id, List.of(
                new PendingMessage("assistant", "patching all",
                        new PendingMessage.ToolPayload(calls, null)),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big),
                new PendingMessage("assistant", big),
                new PendingMessage("user", big)), 0L, 0L));

        String summary = summaryOf(tx.execute(s -> smallBudget.compactContextWindow(id)));

        assertThat(summary).contains("src/F20.java").doesNotContain("src/F21.java");
    }

    @Test
    void compaction_quotesAtMostFiveEarlierRequests() {
        AgentSessionService smallBudget = new AgentSessionService(repository, 20_000, 3);
        Long id = tx.execute(s -> smallBudget.createSession(
                "owner", "repo", 9_000_106L, "Many requests").getId());

        List<PendingMessage> batch = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            batch.add(new PendingMessage("user", "request number " + i));
        }
        String big = "x".repeat(6_000);
        for (int i = 0; i < 5; i++) {
            batch.add(new PendingMessage("assistant", big));
        }
        tx.executeWithoutResult(s -> smallBudget.flushMessages(id, batch, 0L, 0L));

        String summary = summaryOf(tx.execute(s -> smallBudget.compactContextWindow(id)));

        assertThat(summary).contains("request number 3").contains("request number 7")
                .doesNotContain("request number 1").doesNotContain("request number 2");
    }

    @Test
    void compaction_rejectsAWindowOrThresholdThatCannotWork() {
        // A keep of 0 leaves removeCount equal to the window size, so the boundary
        // lookup throws on every follow-up of a long session; fail at construction.
        assertThatThrownBy(() -> new AgentSessionService(repository, 80_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compact-keep-messages");
        assertThatThrownBy(() -> new AgentSessionService(repository, 0, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compact-threshold-chars");
    }
}

