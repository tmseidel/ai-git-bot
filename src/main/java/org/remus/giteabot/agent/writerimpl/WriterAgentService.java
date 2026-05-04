package org.remus.giteabot.agent.writerimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.AgentErrorNotificationService;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class WriterAgentService {

    private static final String WRITER_PROMPT_NAME = "writer";
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final int MAX_INITIAL_TREE_FILES = 100;

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final ToolExecutionService toolExecutionService;
    private final WorkspaceService workspaceService;
    private final String writerAgentSystemPrompt;
    private final String botUsername;
    private final AgentErrorNotificationService errorNotificationService;
    private final WriterPromptBuilder promptBuilder = new WriterPromptBuilder();
    private final WriterResponseParser responseParser = new WriterResponseParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WriterAgentService(RepositoryApiClient repositoryClient,
                              AiClient aiClient,
                              PromptService promptService,
                              AgentConfigProperties agentConfig,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              WorkspaceService workspaceService,
                              String writerAgentSystemPrompt,
                              String botUsername) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.toolExecutionService = toolExecutionService;
        this.workspaceService = workspaceService;
        this.writerAgentSystemPrompt = writerAgentSystemPrompt;
        this.botUsername = botUsername;
        this.errorNotificationService = new AgentErrorNotificationService(repositoryClient);
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();
        String issueRef = normalizeBranchRef(payload.getIssue().getRef());

        if (!isAssignedToThisBot(payload)) {
            log.debug("Ignoring writer assignment for issue #{} because assignee does not match bot '{}'",
                    issueNumber, botUsername);
            return;
        }

        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            if (existingSession.get().getSessionType() != AgentSession.AgentSessionType.WRITER) {
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "🤖 **AI Technical Writer**: A coding-agent session already exists for this issue. "
                                + "Please clone the issue if you want the writer agent to draft a separate improved issue.");
            }
            log.info("Session already exists for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }

        String issueAuthor = findIssueAuthor(owner, repo, issueNumber);
        AgentSession session = null;
        String baseBranch = issueRef != null && !issueRef.isBlank()
                ? issueRef : repositoryClient.getDefaultBranch(owner, repo);
        Path workspaceDir = null;
        try {
            session = sessionService.createSession(owner, repo, issueNumber, issueTitle,
                    AgentSession.AgentSessionType.WRITER, issueAuthor);
            sessionService.setBranchName(session, baseBranch);
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: I've been assigned and will review this issue for completeness.");

            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch, repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();
            String treeContext = promptBuilder.buildTreeContext(
                    repositoryClient.getRepositoryTree(owner, repo, baseBranch), MAX_INITIAL_TREE_FILES);
            runWriterLoop(session, owner, repo, issueNumber, workspaceDir,
                    promptBuilder.buildInitialPrompt(issueNumber, issueTitle, issueBody, treeContext));
        } catch (DataIntegrityViolationException e) {
            log.info("Writer session was created concurrently for issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        } catch (Exception e) {
            log.error("Writer failed while handling assignment for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.FAILED, e);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
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
        if (session.getSessionType() != AgentSession.AgentSessionType.WRITER) {
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: A coding-agent session already exists for this issue. "
                            + "Please clone the issue if you want the writer agent to draft a separate improved issue.");
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.ISSUE_CREATED) {
            log.debug("Writer session already created an issue for #{}", issueNumber);
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
            log.debug("Writer session already running for #{}", issueNumber);
            return;
        }
        if (session.getStatus() == AgentSession.AgentSessionStatus.FAILED) {
            log.debug("Writer session already failed for #{}", issueNumber);
            return;
        }
        if (!isIssueAuthor(owner, repo, issueNumber, payload)) {
            log.debug("Ignoring non-author writer follow-up on issue #{} in {}/{}", issueNumber, owner, repo);
            return;
        }
        Optional<AgentSession> claimedSession = sessionService.claimSessionForUpdate(
                owner, repo, issueNumber, AgentSession.AgentSessionType.WRITER);
        if (claimedSession.isEmpty()) {
            log.debug("Writer session for issue #{} in {}/{} is already claimed or complete",
                    issueNumber, owner, repo);
            return;
        }
        session = claimedSession.get();
        Path workspaceDir = null;
        try {
            String baseBranch = resolveBaseBranch(owner, repo, payload, session);
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch, repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();
            runWriterLoop(session, owner, repo, issueNumber, workspaceDir,
                    promptBuilder.buildContinuationPrompt(payload.getComment().getBody()));
        } catch (Exception e) {
            log.error("Writer failed while handling follow-up for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.IN_PROGRESS, e);
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    private void runWriterLoop(AgentSession session, String owner, String repo,
                               Long issueNumber, Path workspaceDir, String userMessage) {
        String systemPrompt = resolveWriterSystemPrompt();
        String currentMessage = userMessage + "\n\n" + outputContract();
        List<AiMessage> history = new ArrayList<>(sessionService.toAiMessages(session));
        sessionService.addMessage(session, "user", currentMessage);

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            String aiResponse = aiClient.chat(history, currentMessage, systemPrompt, null, agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", aiResponse);
            WriterPlan plan = responseParser.parse(aiResponse);

            if (plan.hasContextRequests() && round >= MAX_TOOL_ROUNDS) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.IN_PROGRESS);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: I need more context before I can continue. "
                                + "Please add more details and mention me again.");
                return;
            }

            if (plan.hasContextRequests() && round < MAX_TOOL_ROUNDS) {
                List<ImplementationPlan.ToolRequest> contextRequests = buildContextRequests(plan);
                BranchSwitchResult branchSwitch = applyRequestedBranchSwitch(
                        workspaceDir, session.getBranchName(), contextRequests, issueNumber);
                if (branchSwitch.selectedBranch() != null
                        && !branchSwitch.selectedBranch().equals(session.getBranchName())) {
                    sessionService.setBranchName(session, branchSwitch.selectedBranch());
                }
                List<ToolResult> results = executeTools(owner, repo, issueNumber, workspaceDir,
                        branchSwitch.remainingToolRequests());
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
                history.add(AiMessage.builder().role("assistant").content(aiResponse).build());
                currentMessage = promptBuilder.buildToolFeedback(branchSwitch.remainingToolRequests(), results);
                sessionService.addMessage(session, "user", currentMessage);
                continue;
            }

            if (plan.hasQuestions() || !plan.isReadyToCreate()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.IN_PROGRESS);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        promptBuilder.buildClarifyingQuestionComment(plan));
                return;
            }

            Long createdIssueNumber = repositoryClient.createIssue(owner, repo,
                    "AI Created Issue: " + session.getIssueTitle(),
                    promptBuilder.buildIssueBody(issueNumber, plan));
            if (createdIssueNumber == null) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: I drafted the improved issue, but creating it failed. "
                                + "Please check the repository provider response and try again.");
                return;
            }
            sessionService.setGeneratedIssueNumber(session, createdIssueNumber);
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: Created improved issue #" + createdIssueNumber
                            + " from this discussion.");
            return;
        }
    }

    private List<ImplementationPlan.ToolRequest> buildContextRequests(WriterPlan plan) {
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        if (plan.getRequestTools() != null) {
            requests.addAll(plan.getRequestTools());
        }
        if (plan.getRequestFiles() != null) {
            int idx = 1;
            for (String file : plan.getRequestFiles()) {
                requests.add(ImplementationPlan.ToolRequest.builder()
                        .id("writer-file-" + idx)
                        .tool("cat")
                        .args(List.of(file))
                        .build());
                idx++;
            }
        }
        return requests;
    }

    private BranchSwitchResult applyRequestedBranchSwitch(Path workspaceDir,
                                                          String baseBranch,
                                                          List<ImplementationPlan.ToolRequest> toolRequests,
                                                          Long issueNumber) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return new BranchSwitchResult(baseBranch, List.of());
        }
        String selectedBranch = baseBranch;
        boolean switched = false;
        List<ImplementationPlan.ToolRequest> remaining = new ArrayList<>();
        for (ImplementationPlan.ToolRequest toolRequest : toolRequests) {
            if (toolRequest == null || toolRequest.getTool() == null || toolRequest.getTool().isBlank()) {
                continue;
            }
            if ("branch-switcher".equalsIgnoreCase(toolRequest.getTool()) && !switched) {
                ToolResult result = toolExecutionService.executeContextTool(
                        workspaceDir, "branch-switcher", toolRequest.getArgs());
                String switchedBranch = extractSwitchedBranch(result);
                if (switchedBranch != null && !switchedBranch.isBlank()) {
                    selectedBranch = switchedBranch;
                    switched = true;
                    log.info("Switched writer workspace/context branch to '{}' for issue #{}",
                            selectedBranch, issueNumber);
                } else {
                    log.warn("Writer branch switch request failed for issue #{}: {}",
                            issueNumber, describeToolFailure(result));
                }
                continue;
            }
            if ("branch-switcher".equalsIgnoreCase(toolRequest.getTool())) {
                log.info("Ignoring additional writer branch-switcher request for issue #{}", issueNumber);
                continue;
            }
            remaining.add(toolRequest);
        }
        return new BranchSwitchResult(selectedBranch, remaining);
    }

    private List<ToolResult> executeTools(String owner, String repo, Long issueNumber,
                                          Path workspaceDir,
                                          List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        for (ImplementationPlan.ToolRequest request : requests) {
            String tool = request.getTool() != null ? request.getTool().strip().toLowerCase() : "";
            List<String> args = request.getArgs() != null ? request.getArgs() : List.of();
            try {
                if ("get-issue".equals(tool)) {
                    Long requestedIssue = parseIssueNumber(args, issueNumber);
                    results.add(new ToolResult(true, 0,
                            toJson(curateIssue(repositoryClient.getIssueDetails(owner, repo, requestedIssue))), ""));
                } else if ("search-issues".equals(tool)) {
                    String query = firstArgOrDefault(args, "");
                    results.add(new ToolResult(true, 0,
                            toJson(repositoryClient.searchIssues(owner, repo, query).stream()
                                    .limit(10)
                                    .map(this::curateIssue)
                                    .toList()), ""));
                } else if (toolExecutionService.isContextTool(tool)) {
                    results.add(toolExecutionService.executeContextTool(workspaceDir, tool, args));
                } else {
                    results.add(new ToolResult(false, -1, "",
                            "Writer tool '" + request.getTool() + "' is not available. Available tools: get-issue, "
                                    + "search-issues, " + String.join(", ", toolExecutionService.getAvailableContextTools())));
                }
            } catch (Exception e) {
                results.add(new ToolResult(false, -1, "", e.getMessage()));
            }
        }
        return results;
    }

    private Long parseIssueNumber(List<String> args, Long defaultIssueNumber) {
        String value = firstArgOrDefault(args, null);
        if (value == null || value.isBlank()) {
            return defaultIssueNumber;
        }
        return Long.parseLong(value);
    }

    private String firstArgOrDefault(List<String> args, String defaultValue) {
        return args == null || args.isEmpty() ? defaultValue : args.getFirst();
    }

    private boolean isIssueAuthor(String owner, String repo, Long issueNumber, WebhookPayload payload) {
        String commenter = payload.getComment() != null && payload.getComment().getUser() != null
                ? payload.getComment().getUser().getLogin() : null;
        if (commenter == null || commenter.isBlank()) {
            return false;
        }
        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isPresent() && sessionOpt.get().getIssueAuthorUsername() != null) {
            return commenter.equalsIgnoreCase(sessionOpt.get().getIssueAuthorUsername());
        }
        String issueAuthor = findIssueAuthor(owner, repo, issueNumber);
        return commenter.equalsIgnoreCase(issueAuthor);
    }

    private boolean isAssignedToThisBot(WebhookPayload payload) {
        if (botUsername == null || botUsername.isBlank() || payload.getIssue() == null) {
            return false;
        }
        if (payload.getIssue().getAssignee() != null
                && botUsername.equalsIgnoreCase(payload.getIssue().getAssignee().getLogin())) {
            return true;
        }
        if (payload.getIssue().getAssignees() != null) {
            return payload.getIssue().getAssignees().stream()
                    .anyMatch(assignee -> assignee != null
                            && botUsername.equalsIgnoreCase(assignee.getLogin()));
        }
        return false;
    }

    private String findIssueAuthor(String owner, String repo, Long issueNumber) {
        Map<String, Object> details = repositoryClient.getIssueDetails(owner, repo, issueNumber);
        for (String key : List.of("user", "author")) {
            Object value = details.get(key);
            if (value instanceof Map<?, ?> userMap) {
                String identity = extractUserIdentity(userMap);
                if (identity != null) {
                    return identity;
                }
            }
        }
        return null;
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

    private Map<String, Object> curateIssue(Map<String, Object> issue) {
        Map<String, Object> curated = new LinkedHashMap<>();
        copyIfPresent(issue, curated, "number");
        copyIfPresent(issue, curated, "title");
        copyIfPresent(issue, curated, "body");
        copyIfPresent(issue, curated, "state");
        copyIfPresent(issue, curated, "url");
        copyIfPresent(issue, curated, "html_url");
        copyUser(issue, curated, "user");
        copyUser(issue, curated, "author");
        return curated;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void copyUser(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> userMap) {
            String identity = extractUserIdentity(userMap);
            if (identity != null) {
                target.put(key, Map.of("login", identity));
            }
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String resolveBaseBranch(String owner, String repo, WebhookPayload payload, AgentSession session) {
        if (session.getBranchName() != null && !session.getBranchName().isBlank()) {
            return session.getBranchName();
        }
        String issueRef = payload.getIssue() != null ? normalizeBranchRef(payload.getIssue().getRef()) : null;
        return issueRef != null && !issueRef.isBlank() ? issueRef : repositoryClient.getDefaultBranch(owner, repo);
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
                  "requestFiles": ["path/to/file"],
                  "requestTools": [{"id": "uuid", "tool": "get-issue", "args": ["123"]}],
                  "clarifyingQuestions": ["question for the issue author"],
                  "revisedIssueDraft": "final markdown issue draft",
                  "assumptions": ["assumption"],
                  "openQuestions": ["remaining non-critical question"],
                  "readyToCreate": true
                }
                Available writer tools: get-issue, search-issues, branch-switcher, rg, ripgrep, grep, find, cat, git-log, git-blame, tree.
                Search-tool args use the shape [pattern, path?, flags?]. Common flags like -i, -n, -l and --include=*.java are supported.
                `find` supports both [glob, path?] and shell-like forms such as ["src/main/java", "-name", "*.java"].
                For alternation in rg/ripgrep/grep patterns, use `|` (not `\\|`).
                You may use requestFiles or read-only repository requestTools when existing issue or repository context is needed before asking or finalizing.
                If you need another base branch, request `branch-switcher` first and wait for its result before requesting files or search results from that branch.
                Do not request repository write tools, file mutation tools, or build/validation tools.
                If critical information is missing, set readyToCreate=false and include clarifyingQuestions.
                If no critical questions remain, set readyToCreate=true and include revisedIssueDraft.
                """;
    }

    private String normalizeBranchRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        return ref;
    }

    private String extractSwitchedBranch(ToolResult result) {
        if (result == null || !result.success() || result.output() == null) {
            return null;
        }
        String prefix = "Switched workspace branch to:";
        int idx = result.output().indexOf(prefix);
        if (idx < 0) {
            return null;
        }
        return normalizeBranchRef(result.output().substring(idx + prefix.length()).trim());
    }

    private String describeToolFailure(ToolResult result) {
        if (result == null) {
            return "unknown tool failure";
        }
        if (result.error() != null && !result.error().isBlank()) {
            return result.error();
        }
        if (result.output() != null && !result.output().isBlank()) {
            return result.output();
        }
        return "tool returned no details";
    }

    private void handleWriterFailure(AgentSession session, String owner, String repo,
                                     Long issueNumber, AgentSession.AgentSessionStatus targetStatus,
                                     Exception e) {
        if (session != null) {
            sessionService.setStatus(session, targetStatus);
        }
        errorNotificationService.postInternalErrorComment(owner, repo, issueNumber,
                "AI Technical Writer",
                "Please try again or mention me again with any additional context.", e);
    }

    private record BranchSwitchResult(String selectedBranch,
                                      List<ImplementationPlan.ToolRequest> remainingToolRequests) {
    }
}
