package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ReviewAgentStrategyLegacyTest {

    @Mock
    private AgentToolRouter toolRouter;

    @Mock
    private ToolExecutionService toolExecutionService;

    private ReviewAgentStrategy strategy() {
        return new ReviewAgentStrategy(
                "system",
                toolRouter,
                new ToolCatalog(new AgentConfigProperties()),
                null,                 // mcp catalog -> defaults to empty
                null,                 // no whitelist
                new AiResponseParser(),
                new BranchSwitcher(toolExecutionService),
                (owner, repo, ref, files) -> "FILE-CONTENT",
                5);
    }

    private AgentRunContext ctx() {
        return new AgentRunContext(null, "octo", "repo", 1L, Path.of("/tmp/ws"), "main");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"length", "unknown", "tool_calls"})
    void legacyLoopRejectsIncompleteProviderResponseBeforeParsing(String finishReason) {
        RestClient.Builder http = RestClient.builder().baseUrl("https://provider.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        var response = AgentJackson.mapper().createObjectNode();
        var choice = response.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.putObject("message").put("content", "A partial review");
        response.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 8);
        server.expect(requestTo("https://provider.example/v1/chat/completions"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        AiClient client = new OpenAiClient(http.build(), "test-model", 32, false, OpenAiFlavor.STANDARD);
        AiResponseParser parser = mock(AiResponseParser.class);
        ReviewAgentStrategy.FileFetcher fetcher = mock(ReviewAgentStrategy.FileFetcher.class);
        ReviewAgentStrategy strategy = new ReviewAgentStrategy("system", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), null, null, parser,
                new BranchSwitcher(toolExecutionService), fetcher, 5);
        AgentSession session = new AgentSession("octo", "repo", 1L, "Review example");
        AgentRunContext context = new AgentRunContext(session, "octo", "repo", 1L, Path.of("/tmp/ws"), "main");
        AgentLoop loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(3, 2, 2, 32, 8_000, 120_000, 200_000, 0.7));

        LoopOutcome outcome = loop.run(context, "Review the change", strategy);

        assertFalse(outcome.success());
        verifyNoInteractions(parser, toolRouter, toolExecutionService, fetcher);
        assertEquals(100L, session.getTotalInputTokens());
        assertEquals(8L, session.getTotalOutputTokens());
        server.verify();
    }

    @Test
    void legacyLoopFetchesRequestedFilesBeforeAcceptingTheFinalReview() {
        AiClient client = mock(AiClient.class);
        when(client.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("{\"summary\":\"Let me inspect the file\",\"requestFiles\":[\"Example.java\"]}",
                                List.of(), StopReason.END_TURN, 100, 12),
                        new ChatTurn("The change correctly handles missing values.", List.of(), StopReason.END_TURN, 120, 20));
        AgentSession session = new AgentSession("octo", "repo", 1L, "Review example");
        AgentRunContext context = new AgentRunContext(session, "octo", "repo", 1L,
                Path.of("/tmp/ws"), "main");
        AgentLoop loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(3, 2, 2, 4000, 8_000, 120_000, 200_000, 0.7));

        LoopOutcome outcome = loop.run(context, "Review the change", strategy());

        assertTrue(outcome.success());
        assertEquals("The change correctly handles missing values.", outcome.payload());
        assertEquals(220L, session.getTotalInputTokens());
        assertEquals(32L, session.getTotalOutputTokens());
        verify(client).chatWithTools(anyList(), contains("FILE-CONTENT"), eq(List.of()), anyString(), isNull(), anyInt());
        verify(client, times(2)).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void legacy_contextRequest_executesToolsAndContinues() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "tool output", ""));

        String json = "{\"summary\":\"exploring\",\"requestTools\":[{\"tool\":\"cat\",\"args\":[\"README.md\"]}]}";
        StepDecision decision = strategy().stepLegacy(ctx(), ChatTurn.text(json), 1);

        // Read-only tool was executed and the loop continues to gather more context.
        verify(toolRouter, times(1)).execute(any(), any());
        StepDecision.Continue cont = assertInstanceOf(StepDecision.Continue.class, decision);
        assertTrue(cont.nextUserMessage().contains("requested repository context"));
        assertTrue(cont.nextUserMessage().contains("tool output"));
    }

    @Test
    void legacy_plainText_isTreatedAsFinalReview() {
        StepDecision decision = strategy().stepLegacy(ctx(), ChatTurn.text("LGTM — the change looks correct."), 1);

        verify(toolRouter, never()).execute(any(), any());
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertTrue(finish.outcome().success());
        assertEquals("LGTM — the change looks correct.", finish.outcome().payload());
    }

    @Test
    void legacyCannotExecuteUnexpectedNativeCallsEvenWithEndTurn() {
        ChatTurn turn = new ChatTurn("Partial review",
                List.of(new ToolCall("call-1", "cat", AgentJackson.mapper().createObjectNode())),
                StopReason.END_TURN, 100, 8);

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                strategy().stepLegacy(ctx(), turn, 1));

        assertFalse(finish.outcome().success());
        verifyNoInteractions(toolRouter, toolExecutionService);
    }

    @Test
    void legacy_contextLimitDoesNotTurnAnotherRequestIntoAReview() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "tool output", ""));
        ReviewAgentStrategy strategy = strategy();
        String request = "{\"summary\":\"Still exploring\",\"requestTools\":[{\"tool\":\"cat\",\"args\":[\"README.md\"]}]}";
        for (int round = 1; round <= 5; round++) {
            assertInstanceOf(StepDecision.Continue.class, strategy.stepLegacy(ctx(), ChatTurn.text(request), round));
        }

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                strategy.stepLegacy(ctx(), ChatTurn.text(request), 6));

        assertFalse(finish.outcome().success());
        verify(toolRouter, times(5)).execute(any(), any());
    }

    @Test
    void onBudgetExhausted_returnsWarning() {
        ReviewAgentStrategy strategy = strategy();
        LoopOutcome outcome = strategy.onBudgetExhausted(ctx());
        assertFalse(outcome.success());
        assertEquals("⚠️ *The review loop reached its round budget limit and could not generate a review.*", outcome.payload());
    }

    @Test
    void finishWithBlank_returnsWarning() {
        ReviewAgentStrategy strategy = strategy();
        // Passing null/blank as the AI response which will end up calling finish() with blank
        StepDecision decision = strategy.stepLegacy(ctx(), ChatTurn.text(""), 1);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertFalse(finish.outcome().success());
        assertEquals("⚠️ *The review feedback was empty or could not be generated by the agent.*", finish.outcome().payload());
    }
}
