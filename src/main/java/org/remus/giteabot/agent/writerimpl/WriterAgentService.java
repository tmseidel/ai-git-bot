package org.remus.giteabot.agent.writerimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class WriterAgentService {

    private static final String WRITER_PROMPT_NAME = "writer";
    private static final int MAX_TOOL_ROUNDS = 3;

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final String writerAgentSystemPrompt;
    private final WriterPromptBuilder promptBuilder = new WriterPromptBuilder();
    private final WriterResponseParser responseParser = new WriterResponseParser();

    public WriterAgentService(RepositoryApiClient repositoryClient,
                              AiClient aiClient,
                              PromptService promptService,
                              AgentConfigProperties agentConfig,
                              AgentSessionService sessionService,
                              String writerAgentSystemPrompt) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.writerAgentSystemPrompt = writerAgentSystemPrompt;
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();

        if (sessionService.getSessionByIssue(owner, repo, issueNumber).isPresent()) {
            log.info("Writer session already exists for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }

        AgentSession session = sessionService.createSession(owner, repo, issueNumber, issueTitle);
        repositoryClient.postComment(owner, repo, issueNumber,
                "🤖 **AI Technical Writer**: I've been assigned and will review this issue for completeness.");
        runWriterLoop(session, owner, repo, issueNumber,
                promptBuilder.buildInitialPrompt(issueNumber, issueTitle, issueBody));
    }

    public void handleIssueComment(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            log.debug("No writer session found for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }
        AgentSession session = sessionOpt.get();
        if (session.getStatus() == AgentSession.AgentSessionStatus.ISSUE_CREATED) {
            log.debug("Writer session already created an issue for #{}", issueNumber);
            return;
        }
        if (!isIssueAuthor(owner, repo, issueNumber, payload)) {
            repositoryClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: I'm waiting for the original issue author to answer before proceeding.");
            return;
        }
        runWriterLoop(session, owner, repo, issueNumber,
                promptBuilder.buildContinuationPrompt(payload.getComment().getBody()));
    }

    private void runWriterLoop(AgentSession session, String owner, String repo,
                               Long issueNumber, String userMessage) {
        String systemPrompt = resolveWriterSystemPrompt();
        String currentMessage = userMessage + "\n\n" + outputContract();
        List<AiMessage> history = new ArrayList<>(sessionService.toAiMessages(session));
        sessionService.addMessage(session, "user", currentMessage);

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            String aiResponse = aiClient.chat(history, currentMessage, systemPrompt, null, agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", aiResponse);
            WriterPlan plan = responseParser.parse(aiResponse);

            if (plan.hasToolRequests() && round < MAX_TOOL_ROUNDS) {
                List<ToolResult> results = executeTools(owner, repo, issueNumber, plan.getRequestTools());
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
                history.add(AiMessage.builder().role("assistant").content(aiResponse).build());
                currentMessage = promptBuilder.buildToolFeedback(plan.getRequestTools(), results);
                sessionService.addMessage(session, "user", currentMessage);
                continue;
            }

            if (plan.hasQuestions() || !plan.isReadyToCreate()) {
                repositoryClient.postComment(owner, repo, issueNumber,
                        promptBuilder.buildClarifyingQuestionComment(plan));
                return;
            }

            Long createdIssueNumber = repositoryClient.createIssue(owner, repo,
                    "AI Created Issue: " + session.getIssueTitle(),
                    promptBuilder.buildIssueBody(issueNumber, plan));
            sessionService.setGeneratedIssueNumber(session, createdIssueNumber);
            repositoryClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: Created improved issue #" + createdIssueNumber
                            + " from this discussion.");
            return;
        }
    }

    private List<ToolResult> executeTools(String owner, String repo, Long issueNumber,
                                          List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        for (ImplementationPlan.ToolRequest request : requests) {
            String tool = request.getTool() != null ? request.getTool().strip().toLowerCase() : "";
            List<String> args = request.getArgs() != null ? request.getArgs() : List.of();
            try {
                if ("get-issue".equals(tool)) {
                    Long requestedIssue = args.isEmpty() ? issueNumber : Long.parseLong(args.getFirst());
                    results.add(new ToolResult(true, 0,
                            repositoryClient.getIssueDetails(owner, repo, requestedIssue).toString(), ""));
                } else if ("search-issues".equals(tool)) {
                    String query = args.isEmpty() ? "" : args.getFirst();
                    results.add(new ToolResult(true, 0,
                            repositoryClient.searchIssues(owner, repo, query).toString(), ""));
                } else {
                    results.add(new ToolResult(false, -1, "",
                            "Writer tool '" + request.getTool() + "' is not available. Available tools: get-issue, search-issues"));
                }
            } catch (Exception e) {
                results.add(new ToolResult(false, -1, "", e.getMessage()));
            }
        }
        return results;
    }

    private boolean isIssueAuthor(String owner, String repo, Long issueNumber, WebhookPayload payload) {
        String commenter = payload.getComment() != null && payload.getComment().getUser() != null
                ? payload.getComment().getUser().getLogin() : null;
        if (commenter == null || commenter.isBlank()) {
            return false;
        }
        Map<String, Object> details = repositoryClient.getIssueDetails(owner, repo, issueNumber);
        Object user = details.get("user");
        if (user instanceof Map<?, ?> userMap) {
            String identity = extractUserIdentity(userMap);
            if (identity != null) {
                return commenter.equalsIgnoreCase(identity);
            }
        }
        Object author = details.get("author");
        if (author instanceof Map<?, ?> authorMap) {
            String identity = extractUserIdentity(authorMap);
            if (identity != null) {
                return commenter.equalsIgnoreCase(identity);
            }
        }
        return false;
    }

    private String extractUserIdentity(Map<?, ?> userMap) {
        for (String key : List.of("login", "username", "name")) {
            Object value = userMap.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private String resolveWriterSystemPrompt() {
        if (writerAgentSystemPrompt != null && !writerAgentSystemPrompt.isBlank()) {
            return writerAgentSystemPrompt;
        }
        return promptService.getSystemPrompt(WRITER_PROMPT_NAME);
    }

    private String outputContract() {
        return """
                
                Return only JSON in this shape:
                {
                  "qualityAssessment": "short assessment",
                  "requestTools": [{"id": "uuid", "tool": "get-issue", "args": ["123"]}],
                  "clarifyingQuestions": ["question for the issue author"],
                  "revisedIssueDraft": "final markdown issue draft",
                  "assumptions": ["assumption"],
                  "openQuestions": ["remaining non-critical question"],
                  "readyToCreate": true
                }
                Available writer tools: get-issue, search-issues.
                Use requestTools only when existing issue content is needed before asking or finalizing.
                If critical information is missing, set readyToCreate=false and include clarifyingQuestions.
                If no critical questions remain, set readyToCreate=true and include revisedIssueDraft.
                """;
    }
}
