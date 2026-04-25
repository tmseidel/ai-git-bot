package org.remus.giteabot.agent;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AgentPromptBuilder;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.issueimpl.IssueNotificationService;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Core issue-implementation (agent) business logic.  Not a Spring-managed
 * singleton — instances are created per-bot by
 * {@link org.remus.giteabot.admin.BotWebhookService} with the bot's own
 * {@link AiClient} and {@link RepositoryApiClient}.
 * <p>
 * All file changes (create, patch, delete) are now performed via tool requests
 * inside the cloned workspace.  After successful validation the workspace is
 * committed and pushed — no separate {@code createOrUpdateFile} API calls are
 * needed.
 */
@Slf4j
public class IssueImplementationService {

    private static final String AGENT_PROMPT_NAME = "agent";
    private static final int MAX_FILE_CONTENT_CHARS = 100_000;
    private static final int MAX_CONTEXT_TOOL_REQUESTS = 5;
    /** Git author identity used for automated commits. */
    private static final String GIT_AUTHOR_NAME  = "AI Agent";
    private static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final ToolExecutionService toolExecutionService;
    private final WorkspaceService workspaceService;

    // Extracted helpers
    private final AiResponseParser responseParser;
    private final AgentPromptBuilder promptBuilder;
    private final IssueNotificationService notificationService;

    public IssueImplementationService(RepositoryApiClient repositoryClient,
                                      AiClient aiClient, PromptService promptService,
                                      AgentConfigProperties agentConfig, AgentSessionService sessionService,
                                      ToolExecutionService toolExecutionService,
                                      WorkspaceService workspaceService) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.toolExecutionService = toolExecutionService;
        this.workspaceService = workspaceService;

        this.responseParser = new AiResponseParser();
        this.promptBuilder = new AgentPromptBuilder();
        this.notificationService = new IssueNotificationService(repositoryClient, responseParser, toolExecutionService);
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner     = payload.getRepository().getOwner().getLogin();
        String repo      = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long   issueNumber  = payload.getIssue().getNumber();
        String issueTitle   = payload.getIssue().getTitle();
        String issueBody    = payload.getIssue().getBody();
        String issueRef     = normalizeBranchRef(payload.getIssue().getRef());

        log.info("Starting implementation for issue #{} '{}' in {}", issueNumber, issueTitle, repoFullName);

        // Check if there's already a session for this issue
        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            log.info("Session already exists for issue #{}, skipping initial implementation", issueNumber);
            return;
        }

        AgentSession session = sessionService.createSession(owner, repo, issueNumber, issueTitle);
        Path workspaceDir = null;

        try {
            repositoryClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: I've been assigned to this issue. Analyzing repository structure...");

            // Determine base branch
            String baseBranch;
            if (issueRef != null && !issueRef.isBlank()) {
                baseBranch = issueRef;
                log.info("Using issue branch '{}' as base for issue #{}", baseBranch, issueNumber);
            } else {
                baseBranch = repositoryClient.getDefaultBranch(owner, repo);
                log.info("No issue branch set, using default branch '{}' for issue #{}", baseBranch, issueNumber);
            }

            // Clone repository once — all operations happen in this workspace
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, baseBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();

            // Fetch repository tree for context
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, baseBranch);
            String treeContext  = promptBuilder.buildTreeContext(tree);
            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);

            // STEP 1: Ask AI which context it needs
            log.info("Step 1: Asking AI which files are needed for issue #{}", issueNumber);
            String fileRequestPrompt = promptBuilder.buildFileRequestPrompt(issueTitle, issueBody, treeContext);
            sessionService.addMessage(session, "user", fileRequestPrompt);

            String fileRequestResponse = aiClient.chat(new ArrayList<>(), fileRequestPrompt, systemPrompt,
                    null, agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", fileRequestResponse);

            ImplementationPlan initialContextPlan = responseParser.parseAiResponse(fileRequestResponse);
            List<String> requestedFiles = initialContextPlan != null && initialContextPlan.getRequestFiles() != null
                    ? initialContextPlan.getRequestFiles()
                    : responseParser.parseRequestedFiles(fileRequestResponse, tree);
            List<ImplementationPlan.ToolRequest> requestedTools =
                    initialContextPlan != null ? initialContextPlan.getRequestTools() : List.of();
            log.info("AI requested {} files and {} repository tools for context",
                    requestedFiles.size(), requestedTools != null ? requestedTools.size() : 0);

            BranchSwitchResult branchSwitchResult = applyRequestedBranchSwitch(
                    workspaceDir, baseBranch, requestedTools, issueNumber);
            baseBranch = branchSwitchResult.selectedBranch();
            requestedTools = branchSwitchResult.remainingToolRequests();
            if (!baseBranch.equals(branchSwitchResult.initialBranch())) {
                tree = repositoryClient.getRepositoryTree(owner, repo, baseBranch);
                treeContext = promptBuilder.buildTreeContext(tree);
            }

            String fileContext = fetchRequestedContext(owner, repo, baseBranch,
                    requestedFiles, requestedTools, workspaceDir);

            // STEP 2: Generate implementation via tool requests
            log.info("Step 2: Generating implementation for issue #{}", issueNumber);
            String implementationPrompt = promptBuilder.buildImplementationPromptWithContext(
                    issueTitle, issueBody, treeContext, fileContext);

            // Add tools info to prompt
            String toolsInfo = buildToolsInfo();
            String fullPrompt = implementationPrompt + toolsInfo;

            ToolImplementationLoopResult implementationResult = runToolImplementationLoop(
                    session, fullPrompt, systemPrompt, workspaceDir, owner, repo, issueNumber, baseBranch);
            boolean implementationSucceeded = implementationResult.success();
            baseBranch = implementationResult.selectedBranch();

            if (!implementationSucceeded) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        """
                        🤖 **AI Agent**: I was unable to produce a valid implementation for this issue. \
                        The issue may be too complex or ambiguous for automated implementation.

                        You can mention me in a comment to provide more details or clarification.""");
                return;
            }

            // Commit and push to new feature branch
            String branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
            sessionService.setBranchName(session, branchName);

            String commitMessage = String.format("agent: implement #%d - %s", issueNumber, issueTitle);
            boolean pushed = workspaceService.commitAndPush(workspaceDir, branchName, commitMessage,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, true);

            if (!pushed) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Implementation succeeded but pushing the branch failed. Please check the logs.");
                return;
            }

            // Create pull request
            ImplementationPlan lastPlan = getLastPlanFromSession(session);
            String prTitle = String.format("AI Agent: %s (fixes #%d)", issueTitle, issueNumber);
            String prBody  = promptBuilder.buildPrBody(issueNumber, lastPlan != null ? lastPlan
                    : ImplementationPlan.builder().summary("Automated implementation").build());
            Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                    branchName, baseBranch);

            sessionService.setPrNumber(session, prNumber);
            notificationService.postSuccessComment(owner, repo, issueNumber, lastPlan, prNumber);
            log.info("Successfully created PR #{} for issue #{} in {}", prNumber, issueNumber, repoFullName);

        } catch (Exception e) {
            log.error("Failed to implement issue #{} in {}: {}", issueNumber, repoFullName, e.getMessage(), e);
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("""
                                🤖 **AI Agent**: Implementation failed with error: `%s`

                                You can mention me in a comment to try again with more details.""",
                                e.getMessage()));
            } catch (Exception ce) {
                log.error("Failed to post failure comment on issue #{}: {}", issueNumber, ce.getMessage());
            }
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Unified tool-based implementation loop — used by both {@link #handleIssueAssigned} and
     * {@link #handleIssueComment}.
     * <p>
     * The AI proposes a list of {@code runTools} (file modifications + validation).  All tools
     * are executed in the cloned workspace.  Validation results are fed back to the AI on
     * failure so it can self-correct.
     * <p>
     * The current session conversation (stored via {@link AgentSessionService}) is used as
     * context for every AI call, so earlier dialogue (file-request step, prior comments) is
     * always visible to the model.
     *
     * @param userMessage the next user turn to kick off this loop (will be appended to the session)
     * @return {@code true} when the implementation is considered successful
     */
    private ToolImplementationLoopResult runToolImplementationLoop(
            AgentSession session, String userMessage, String systemPrompt,
            Path workspaceDir, String owner, String repo, Long issueNumber,
            String initialContextBranch) {

        int maxRetries    = agentConfig.getValidation().isEnabled()
                ? agentConfig.getValidation().getMaxRetries() : 1;
        int maxToolRounds = agentConfig.getValidation().getMaxToolExecutions();
        int fileRequestRounds = 0;
        int toolRounds    = 0;
        String currentContextBranch = initialContextBranch;

        // Snapshot the prior conversation history (file-request dialogue, earlier comments, …)
        // so it is visible to the model as context.  The list is then extended locally as the
        // loop progresses — we do not re-query the session on every iteration so that mocks
        // in tests remain simple.
        List<AiMessage> history = new ArrayList<>(sessionService.toAiMessages(session));
        sessionService.addMessage(session, "user", userMessage);
        String currentMessage = userMessage;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("Tool implementation loop for issue #{}, attempt {}/{}", issueNumber, attempt, maxRetries);

            String aiResponse = aiClient.chat(history, currentMessage, systemPrompt,
                    null, agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", aiResponse);
            notificationService.postAiThinkingComment(owner, repo, issueNumber, aiResponse);

            ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);
            if (plan == null) {
                log.warn("Failed to parse AI response on attempt {}", attempt);
                return new ToolImplementationLoopResult(false, currentContextBranch);
            }

            // Context request before implementation — does not count as an implementation attempt
            if (plan.hasContextRequests() && !plan.hasToolRequest() && fileRequestRounds < 3) {
                fileRequestRounds++;
                log.info("AI requesting additional context (round {}/3)", fileRequestRounds);
                BranchSwitchResult branchSwitchResult = applyRequestedBranchSwitch(
                        workspaceDir, currentContextBranch, plan.getRequestTools(), issueNumber);
                currentContextBranch = branchSwitchResult.selectedBranch();
                String ctx = fetchRequestedContext(owner, repo, currentContextBranch,
                        plan.getRequestFiles(), branchSwitchResult.remainingToolRequests(), workspaceDir);
                String ctxMsg = "Here is the requested repository context:\n" + ctx
                        + "\n\nNow implement the issue using `runTools`. "
                        + "Use write-file/patch-file for changes and include validation tools.";
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
                history.add(AiMessage.builder().role("assistant").content(aiResponse).build());
                currentMessage = ctxMsg;
                sessionService.addMessage(session, "user", ctxMsg);
                attempt--; // doesn't count as an implementation attempt
                continue;
            }

            // No tool requests at all → ask AI to produce them
            if (!plan.hasToolRequest()) {
                log.info("AI provided no runTools on attempt {}", attempt);
                String feedbackMsg = promptBuilder.buildMissingToolFeedback();
                history.add(AiMessage.builder().role("user").content(currentMessage).build());
                history.add(AiMessage.builder().role("assistant").content(aiResponse).build());
                currentMessage = feedbackMsg;
                sessionService.addMessage(session, "user", feedbackMsg);
                continue;
            }

            // Guard against runaway tool rounds
            if (toolRounds >= maxToolRounds) {
                log.warn("Reached max tool rounds ({}) — returning current result", maxToolRounds);
                return new ToolImplementationLoopResult(false, currentContextBranch);
            }
            toolRounds++;

            List<ImplementationPlan.ToolRequest> requests = plan.getEffectiveToolRequests();
            List<ToolResult> results = executeAllTools(workspaceDir, requests);

            // Post only non-silent (validation) tool results as comments
            for (int i = 0; i < requests.size(); i++) {
                ImplementationPlan.ToolRequest req = requests.get(i);
                if (!toolExecutionService.isSilentTool(req.getTool())) {
                    notificationService.postToolResultComment(owner, repo, issueNumber, req, results.get(i));
                }
            }

            // Validation disabled → treat as success after first tool execution
            if (!agentConfig.getValidation().isEnabled()) {
                return new ToolImplementationLoopResult(true, currentContextBranch);
            }

            // No validation tools present → file-only changes, consider success
            if (!hasValidationTools(requests)) {
                return new ToolImplementationLoopResult(true, currentContextBranch);
            }

            if (allValidationToolsPassed(requests, results)) {
                log.info("All validation tools passed on attempt {}", attempt);
                return new ToolImplementationLoopResult(true, currentContextBranch);
            }

            // Validation failed → give feedback and let the AI retry
            String feedback = promptBuilder.buildMultiToolFeedback(requests, results);
            history.add(AiMessage.builder().role("user").content(currentMessage).build());
            history.add(AiMessage.builder().role("assistant").content(aiResponse).build());
            currentMessage = feedback;
            sessionService.addMessage(session, "user", feedback);
        }

        log.warn("Tool implementation loop exhausted {} attempts without full success", maxRetries);
        return new ToolImplementationLoopResult(false, currentContextBranch);
    }

    /**
     * Returns {@code true} if {@code requests} contains at least one configured validation tool
     * (e.g. {@code mvn}, {@code npm}).  Uses {@link ToolExecutionService#isValidationTool} which
     * checks the configured {@code available-tools} list — not the weaker {@code !isSilentTool}.
     */
    private boolean hasValidationTools(List<ImplementationPlan.ToolRequest> requests) {
        return requests.stream()
                .anyMatch(r -> toolExecutionService.isValidationTool(r.getTool()));
    }
    /**
     * Returns {@code true} if every validation tool in {@code requests} produced a successful result.
     * When there are <em>no</em> validation tools this returns {@code true} vacuously — callers must
     * guard with {@link #hasValidationTools} when they need to distinguish "nothing to validate" from
     * "all validations passed".
     */
    private boolean allValidationToolsPassed(List<ImplementationPlan.ToolRequest> requests,
                                              List<ToolResult> results) {
        return IntStream.range(0, requests.size())
                .filter(i -> toolExecutionService.isValidationTool(requests.get(i).getTool()))
                .allMatch(i -> results.get(i).success());
    }
    /** Execute each tool and collect results (file tools via executeFileTool, others via context/validation). */
    private List<ToolResult> executeAllTools(Path workspaceDir,
                                              List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        for (ImplementationPlan.ToolRequest req : requests) {
            log.info("Executing tool: {} {}", req.getTool(),
                    req.getArgs() != null ? String.join(" ", req.getArgs()) : "");
            ToolResult res;
            if (toolExecutionService.isFileTool(req.getTool())) {
                res = toolExecutionService.executeFileTool(workspaceDir, req.getTool(), req.getArgs());
            } else if (toolExecutionService.isContextTool(req.getTool())) {
                res = toolExecutionService.executeContextTool(workspaceDir, req.getTool(), req.getArgs());
            } else {
                res = toolExecutionService.executeTool(workspaceDir, req.getTool(), req.getArgs());
            }
            results.add(res);
        }
        return results;
    }

    /**
     * Handles a comment on an issue that mentions the bot.
     */
    public void handleIssueComment(WebhookPayload payload) {
        String owner       = payload.getRepository().getOwner().getLogin();
        String repo        = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long   issueNumber  = payload.getIssue().getNumber();
        Long   commentId    = payload.getComment().getId();
        String commentBody  = payload.getComment().getBody();

        log.info("Handling agent comment #{} on issue #{} in {}", commentId, issueNumber, repoFullName);

        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            log.debug("No agent session found for issue #{}, trying PR number lookup", issueNumber);
            sessionOpt = sessionService.getSessionByPr(owner, repo, issueNumber);
        }
        if (sessionOpt.isEmpty()) {
            log.info("No agent session found for issue/PR #{}, ignoring comment", issueNumber);
            return;
        }

        AgentSession session = sessionOpt.get();
        Path workspaceDir = null;

        try {
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);

            String branchName    = session.getBranchName();
            String defaultBranch = repositoryClient.getDefaultBranch(owner, repo);
            String workingBranch = branchName != null ? branchName : defaultBranch;

            // Clone working branch into fresh workspace
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    owner, repo, workingBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!wsResult.success()) {
                repositoryClient.postComment(owner, repo, issueNumber,
                        "⚠️ **AI Agent**: Failed to prepare workspace: " + wsResult.error());
                return;
            }
            workspaceDir = wsResult.workspacePath();

            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);
            String userMessage  = promptBuilder.buildContinuationPrompt(commentBody);

            log.info("Requesting AI to continue implementation for issue #{}", issueNumber);
            // runToolImplementationLoop handles: AI call, context rounds, tool execution, retries
            ToolImplementationLoopResult implementationResult = runToolImplementationLoop(
                    session, userMessage, systemPrompt, workspaceDir, owner, repo, issueNumber, workingBranch);
            boolean success = implementationResult.success();
            String selectedContextBranch = implementationResult.selectedBranch();
            if (!success) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Validation failed and I couldn't fix the issues. " +
                        "Please check the tool output above and provide more guidance.");
                return;
            }

            // Commit and push
            boolean createNew = (branchName == null);
            if (createNew) {
                branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
                sessionService.setBranchName(session, branchName);
            }
            String commitMessage = String.format("agent: follow-up for #%d", issueNumber);
            boolean pushed = workspaceService.commitAndPush(workspaceDir, branchName, commitMessage,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, createNew);
            if (!pushed) {
                repositoryClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: Tool execution succeeded but pushing changes failed.");
                return;
            }

            // Create PR if not yet existing
            if (session.getPrNumber() == null) {
                String prTitle = String.format("AI Agent: %s (fixes #%d)", session.getIssueTitle(), issueNumber);
                ImplementationPlan latestPlan = getLastPlanFromSession(session);
                String prBody = promptBuilder.buildPrBody(issueNumber, latestPlan != null ? latestPlan
                        : ImplementationPlan.builder().summary("Automated implementation").build());
                Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                        branchName, createNew ? selectedContextBranch : defaultBranch);
                sessionService.setPrNumber(session, prNumber);
            }

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
            notificationService.postFollowUpSuccessComment(owner, repo, issueNumber,
                    getLastPlanFromSession(session), session.getPrNumber());
            log.info("Successfully applied follow-up changes for issue #{}", issueNumber);

        } catch (Exception e) {
            log.error("Failed to handle comment on issue #{}: {}", issueNumber, e.getMessage(), e);
            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("""
                                🤖 **AI Agent**: Failed to process your request: `%s`

                                Please try again or provide more details.""", e.getMessage()));
            } catch (Exception ce) {
                log.error("Failed to post error comment on issue #{}: {}", issueNumber, ce.getMessage());
            }
            if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
                sessionService.setStatus(session,
                        session.getPrNumber() != null ? AgentSession.AgentSessionStatus.PR_CREATED
                                                      : AgentSession.AgentSessionStatus.FAILED);
            }
        } finally {
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private String buildToolsInfo() {
        List<String> availableTools = toolExecutionService.getAvailableTools();
        return "\n\n**Available file tools** (all silent — results go back to you only): "
                + String.join(", ", toolExecutionService.getAvailableFileTools())
                + "\n**Available context tools** (silent): "
                + String.join(", ", toolExecutionService.getAvailableContextTools())
                + "\n**Available validation tools** (results posted as comments): "
                + String.join(", ", availableTools);
    }

    /**
     * Fetches any additional repository context requested by the AI.
     * Context tools are run against the already-cloned {@code workspaceDir}.
     */
    private String fetchRequestedContext(String owner, String repo, String ref,
                                         List<String> filePaths,
                                         List<ImplementationPlan.ToolRequest> toolRequests,
                                         Path workspaceDir) {
        StringBuilder sb = new StringBuilder();

        if (filePaths != null && !filePaths.isEmpty()) {
            sb.append("## Requested Files\n");
            sb.append(fetchSpecificFiles(owner, repo, ref, filePaths));
        }

        String toolOutput = executeRequestedContextTools(workspaceDir, toolRequests);
        if (!toolOutput.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("## Repository Tool Results\n").append(toolOutput);
        }

        return sb.isEmpty() ? "No additional repository context could be retrieved." : sb.toString();
    }

    private BranchSwitchResult applyRequestedBranchSwitch(Path workspaceDir,
                                                          String baseBranch,
                                                          List<ImplementationPlan.ToolRequest> toolRequests,
                                                          Long issueNumber) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return new BranchSwitchResult(baseBranch, baseBranch, List.of());
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
                    log.info("Switched workspace/context branch to '{}' for issue #{}",
                            selectedBranch, issueNumber);
                } else {
                    log.warn("Branch switch request failed for issue #{}: {}",
                            issueNumber, describeToolFailure(result));
                }
                continue;
            }

            if ("branch-switcher".equalsIgnoreCase(toolRequest.getTool())) {
                log.info("Ignoring additional branch-switcher request for issue #{}", issueNumber);
                continue;
            }

            remaining.add(toolRequest);
        }

        return new BranchSwitchResult(baseBranch, selectedBranch, remaining);
    }

    private String executeRequestedContextTools(Path workspaceDir,
                                                List<ImplementationPlan.ToolRequest> toolRequests) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int toolCount = 0;
        for (ImplementationPlan.ToolRequest toolRequest : toolRequests) {
            if (toolRequest == null || toolRequest.getTool() == null || toolRequest.getTool().isBlank()) {
                continue;
            }
            if (toolCount >= MAX_CONTEXT_TOOL_REQUESTS) {
                sb.append("\nAdditional tool requests were skipped after reaching the per-round limit of ")
                        .append(MAX_CONTEXT_TOOL_REQUESTS).append(".\n");
                break;
            }
            toolCount++;
            ToolResult result = toolExecutionService.executeContextTool(
                    workspaceDir, toolRequest.getTool(), toolRequest.getArgs());
            sb.append("### `").append(toolRequest.getTool());
            if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
                sb.append(" ").append(String.join(" ", toolRequest.getArgs()));
            }
            sb.append("`\n");
            if (result.success()) {
                String output = result.output();
                sb.append(output == null || output.isBlank() ? "(no output)" : output).append("\n\n");
            } else {
                sb.append("Failed: ").append(describeToolFailure(result))
                        .append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * Fetches specific file contents from the repository.
     */
    private String fetchSpecificFiles(String owner, String repo, String ref, List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;

        for (String path : filePaths) {
            if (totalChars > MAX_FILE_CONTENT_CHARS) {
                sb.append("\n(File context truncated due to size limits)\n");
                break;
            }
            try {
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
                if (content != null && !content.isEmpty()) {
                    sb.append("\n--- File: ").append(path).append(" ---\n");
                    sb.append(content).append("\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Normalizes a branch reference by removing the "refs/heads/" prefix if present.
     */
    private String normalizeBranchRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    private String extractSwitchedBranch(ToolResult result) {
        if (result == null || !result.success()) {
            return null;
        }
        String output = result.output();
        if (output == null || output.isBlank()) {
            return null;
        }
        String prefix = "Switched workspace branch to:";
        int idx = output.indexOf(prefix);
        if (idx < 0) {
            return null;
        }
        String branch = output.substring(idx + prefix.length()).trim();
        return normalizeBranchRef(branch);
    }

    private String describeToolFailure(ToolResult result) {
        if (result == null) {
            return "unknown tool failure";
        }
        String error = result.error();
        if (error != null && !error.isBlank()) {
            return error;
        }
        String output = result.output();
        if (output != null && !output.isBlank()) {
            return output;
        }
        return "tool returned no details";
    }

    /**
     * Result of processing an optional branch-switch request from AI context tools.
     *
     * @param initialBranch        branch used before evaluating branch-switcher requests
     * @param selectedBranch       final selected base branch after evaluating branch-switcher
     * @param remainingToolRequests non-branch-switcher tool requests that should still be executed
     */
    private record BranchSwitchResult(String initialBranch,
                                      String selectedBranch,
                                      List<ImplementationPlan.ToolRequest> remainingToolRequests) {
    }

    /** Result of the implementation loop including the final branch used for context lookups. */
    private record ToolImplementationLoopResult(boolean success, String selectedBranch) {
    }

    /** Retrieves the last parsed plan from the session history (the latest assistant JSON response). */
    private ImplementationPlan getLastPlanFromSession(AgentSession session) {
        List<AiMessage> messages = sessionService.toAiMessages(session);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                ImplementationPlan p = responseParser.parseAiResponse(messages.get(i).getContent());
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }
}
