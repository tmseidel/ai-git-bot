package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewRoundBudgetReminderTest {

    private static final String REMINDER_MARKER = "Conclude your review now";

    @ParameterizedTest
    @CsvSource({"3,true", "3,false", "20,true", "20,false", "60,true", "60,false"})
    void remindsBeforeTheRoundTwoBeforeTheLimitAndPreservesContext(int maxRounds, boolean nativeMode) {
        var requests = new ArrayList<Request>();

        LoopOutcome outcome = runReview(new Scenario(maxRounds, nativeMode, maxRounds), requests, false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.payload()).isEqualTo("Final review");
        assertThat(requests).hasSize(maxRounds);
        assertThat(requests).allSatisfy(request -> assertThat(request.systemPrompt()).isEqualTo("system"));
        int reminderIndex = maxRounds - 3;
        assertThat(requests.subList(0, reminderIndex)).allSatisfy(request ->
                assertThat(reminders(request)).isEmpty());
        Request closing = requests.get(reminderIndex);
        assertThat(reminders(closing)).hasSize(1);
        assertThat(closing.history().getLast().getRole()).isEqualTo("user");
        assertThat(closing.history().getLast().getContent()).contains(REMINDER_MARKER);
        if (reminderIndex > 0) {
            var previousHistory = requests.get(reminderIndex - 1).history();
            assertThat(closing.history().subList(0, previousHistory.size())).isEqualTo(previousHistory);
        }
        assertThat(requests.subList(reminderIndex, maxRounds)).allSatisfy(request ->
                assertThat(reminders(request)).hasSize(1));
        if (nativeMode) {
            assertThat(closing.tools()).isNotEmpty().isEqualTo(requests.getFirst().tools());
            if (reminderIndex > 0) {
                assertThat(closing.history()).anySatisfy(message -> {
                    assertThat(message.getRole()).isEqualTo("tool");
                    assertThat(message.getToolCallId()).isEqualTo("call_" + reminderIndex);
                    assertThat(message.getToolResult()).contains("Repository evidence");
                });
            }
        } else {
            if (reminderIndex > 0) {
                assertThat(closing.message()).contains("FILE-CONTENT");
            }
            assertThat(closing.tools()).isEmpty();
        }
        assertThat(requests.getFirst().message()).isEqualTo("Review the change");
    }

    @Test
    void keepsBudgetExhaustionAsFailureWhenTheModelContinuesExploring() {
        var requests = new ArrayList<Request>();

        LoopOutcome outcome = runReview(new Scenario(3, true, 0), requests, false);

        assertThat(outcome.success()).isFalse();
        assertThat(requests).hasSize(3);
        assertThat(reminders(requests.getLast())).hasSize(1);
    }

    @Test
    void doesNotAddAReminderWhenTheReviewFinishesEarly() {
        var requests = new ArrayList<Request>();

        LoopOutcome outcome = runReview(new Scenario(60, true, 1), requests, false);

        assertThat(outcome.success()).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(reminders(requests.getFirst())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "14,true,3,12,10", "14,false,3,12,1",
            "60,true,10,30,28", "60,false,10,60,8",
            "60,true,1,60,58", "60,false,60,1,58",
            "3,true,100,100,1", "2,true,3,1,1", "4,false,2,12,1"
    })
    void remindsBeforeTheFirstApplicableBudgetAndOnlyOnce(int maxRounds, boolean nativeMode,
            int maxContextRounds, int maxToolRounds, int reminderRound) {
        var requests = new ArrayList<Request>();
        int finishRound = Math.min(maxRounds, (nativeMode ? maxToolRounds : maxContextRounds) + 1);

        LoopOutcome outcome = runReview(new Scenario(maxRounds, nativeMode, finishRound,
                maxContextRounds, maxToolRounds), requests, false);

        assertThat(outcome.success()).isTrue();
        assertThat(requests).hasSize(finishRound);
        assertThat(requests.subList(0, reminderRound - 1)).allSatisfy(request ->
                assertThat(reminders(request)).isEmpty());
        assertThat(requests.subList(reminderRound - 1, finishRound)).allSatisfy(request ->
                assertThat(reminders(request)).hasSize(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsStrategyBudgetExhaustionAsFailureAfterTheReminder(boolean nativeMode) {
        var requests = new ArrayList<Request>();

        LoopOutcome outcome = runReview(new Scenario(60, nativeMode, 0, 3, 12), requests, false);

        assertThat(outcome.success()).isFalse();
        assertThat(requests).hasSize(nativeMode ? 13 : 4);
        assertThat(reminders(requests.getLast())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsClosingReminderWhenFinalTurnRetriesAfterCompaction(boolean nativeMode) {
        var requests = new ArrayList<Request>();

        LoopOutcome outcome = runReview(new Scenario(3, nativeMode, 3), requests, true);

        assertThat(outcome.success()).isTrue();
        assertThat(requests).hasSize(4);
        assertThat(requests).allSatisfy(request -> assertThat(request.systemPrompt()).isEqualTo("system"));
        assertThat(reminders(requests.get(2))).hasSize(1);
        assertThat(reminders(requests.get(3))).hasSize(1);
        assertThat(requests.get(2).history()).noneSatisfy(message ->
                assertThat(message.getContent()).contains("aggressively compacted"));
        assertThat(requests.get(3).history()).anySatisfy(message ->
                assertThat(message.getContent()).contains("aggressively compacted"));
        assertThat(requests.get(3).message()).isEqualTo(requests.get(2).message());
        assertThat(requests.get(3).tools()).isEqualTo(requests.get(2).tools());
    }

    private LoopOutcome runReview(Scenario scenario, List<Request> requests, boolean retryFinalTurn) {
        AiClient client = mock(AiClient.class);
        when(client.supportsNativeTools()).thenReturn(scenario.nativeMode());
        if (retryFinalTurn) {
            when(client.isPromptTooLongError(any())).thenReturn(true);
        }
        when(client.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    requests.add(new Request(List.copyOf(invocation.getArgument(0)),
                            invocation.getArgument(1), List.copyOf(invocation.getArgument(2)),
                            invocation.getArgument(3)));
                    int round = requests.size();
                    if (retryFinalTurn && round == scenario.finishRound()) {
                        throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Prompt exceeds context window");
                    }
                    if (scenario.finishRound() > 0 && round >= scenario.finishRound()) {
                        return ChatTurn.text("Final review");
                    }
                    var arguments = AgentJackson.mapper().createObjectNode();
                    arguments.putArray("args").add("Example.java");
                    return scenario.nativeMode()
                            ? new ChatTurn("Inspecting", List.of(new ToolCall("call_" + round, "cat", arguments)),
                                    StopReason.TOOL_USE, 0, 0)
                            : ChatTurn.text("{\"requestFiles\":[\"Example.java\"]}");
                });
        AgentToolRouter router = mock(AgentToolRouter.class);
        if (scenario.nativeMode() && scenario.finishRound() != 1) {
            String evidence = retryFinalTurn ? "Repository evidence".repeat(7000) : "Repository evidence";
            when(router.execute(any(), any())).thenReturn(new ToolResult(true, 0, evidence, ""));
        }
        String fileContent = retryFinalTurn ? "FILE-CONTENT".repeat(11000) : "FILE-CONTENT";
        var strategy = new ReviewAgentStrategy("system", router, new ToolCatalog(new AgentConfigProperties()),
                null, Set.of("cat"), new AiResponseParser(), new BranchSwitcher(mock(ToolExecutionService.class)),
                (owner, repo, ref, files) -> fileContent, scenario.maxContextRounds(), scenario.maxToolRounds());
        var session = new AgentSession("owner", "repo", 76L, "Review example");
        var context = new AgentRunContext(session, "owner", "repo", 76L, Workspace.unmanaged(Path.of("/tmp/ws")), "main");
        var loop = new AgentLoop(client, new AgentSessionService(mock(AgentSessionRepository.class)),
                new AgentBudget(scenario.maxRounds(), scenario.maxContextRounds(), 2, 4000, 8000, 120000, 200000, 0.7));
        return loop.run(context, "Review the change", strategy);
    }

    private List<String> reminders(Request request) {
        return Stream.concat(request.history().stream().filter(message -> "user".equals(message.getRole()))
                        .map(AiMessage::getContent), Stream.of(request.systemPrompt()))
                .filter(content -> content != null && content.contains(REMINDER_MARKER)).toList();
    }

    private record Scenario(int maxRounds, boolean nativeMode, int finishRound,
                            int maxContextRounds, int maxToolRounds) {
        private Scenario(int maxRounds, boolean nativeMode, int finishRound) {
            this(maxRounds, nativeMode, finishRound, 100, 100);
        }
    }
    private record Request(List<AiMessage> history, String message, List<ToolDescriptor> tools, String systemPrompt) {}
}
