package org.remus.giteabot.agent;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AgentPromptBuilder;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.issueimpl.FileChangeApplier;
import org.remus.giteabot.agent.issueimpl.IssueNotificationService;
import org.remus.giteabot.agent.model.FileChange;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core issue-implementation (agent) business logic.  Not a Spring-managed
 * singleton — instances are created per-bot by
 * {@link org.remus.giteabot.admin.BotWebhookService} with the bot's own
 * {@link AiClient} and {@link RepositoryApiClient}.
 * <p>
 * This class contains only the agent orchestration loops and immediate
 * operations.  Parsing, prompt building, file-change application, and
 * notification posting are delegated to helper classes in the
 * {@code org.remus.giteabot.agent.issueimpl} package.
 */
@Slf4j
public class IssueImplementationService {

    private static final String AGENT_PROMPT_NAME = "agent";
    private static final int MAX_FILE_CONTENT_CHARS = 100000;
    private static final int MAX_CONTEXT_TOOL_REQUESTS = 5;

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
    private final FileChangeApplier fileChangeApplier;
    private final IssueNotificationService notificationService;

    public IssueImplementationService(RepositoryApiClient repositoryClient,
                                      AiClient aiClient, PromptService promptService,
                                      AgentConfigProperties agentConfig, AgentSessionService sessionService,
                                      ToolExecutionService toolExecutionService,
                                      WorkspaceService workspaceService,
                                      DiffApplyService diffApplyService) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.toolExecutionService = toolExecutionService;
        this.workspaceService = workspaceService;

        this.responseParser = new AiResponseParser();
        this.promptBuilder = new AgentPromptBuilder();
        this.fileChangeApplier = new FileChangeApplier(repositoryClient, diffApplyService,
                aiClient, sessionService, agentConfig);
        this.notificationService = new IssueNotificationService(repositoryClient, responseParser);
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();
        String issueRef = normalizeBranchRef(payload.getIssue().getRef());

        log.info("Starting implementation for issue #{} '{}' in {}", issueNumber, issueTitle, repoFullName);

        // Check if there's already a session for this issue
        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            log.info("Session already exists for issue #{}, skipping initial implementation", issueNumber);
            return;
        }

        // Create a new session
        AgentSession session = sessionService.createSession(owner, repo, issueNumber, issueTitle);

        String branchName = null;
        try {
            // Post initial progress comment
            repositoryClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: I've been assigned to this issue. Analyzing repository structure...");

            // Determine base branch: use issue ref if set, otherwise default branch
            String baseBranch;
            if (issueRef != null && !issueRef.isBlank()) {
                baseBranch = issueRef;
                log.info("Using issue branch '{}' as base for issue #{}", baseBranch, issueNumber);
            } else {
                baseBranch = repositoryClient.getDefaultBranch(owner, repo);
                log.info("No issue branch set, using default branch '{}' for issue #{}", baseBranch, issueNumber);
            }

            // Fetch repository tree
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, baseBranch);
            String treeContext = promptBuilder.buildTreeContext(tree);

            // Get system prompt for agent
            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);

            // STEP 1: Ask AI which files it needs
            log.info("Step 1: Asking AI which files are needed for issue #{}", issueNumber);
            String fileRequestPrompt = promptBuilder.buildFileRequestPrompt(issueTitle, issueBody, treeContext);
            sessionService.addMessage(session, "user", fileRequestPrompt);

            String fileRequestResponse = aiClient.chat(new ArrayList<>(), fileRequestPrompt, systemPrompt, null,
                    agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", fileRequestResponse);

            ImplementationPlan initialContextPlan = responseParser.parseAiResponse(fileRequestResponse);
            List<String> requestedFiles = initialContextPlan != null && initialContextPlan.getRequestFiles() != null
                    ? initialContextPlan.getRequestFiles()
                    : responseParser.parseRequestedFiles(fileRequestResponse, tree);
            List<ImplementationPlan.ToolRequest> requestedTools =
                    initialContextPlan != null ? initialContextPlan.getRequestTools() : List.of();
            log.info("AI requested {} files and {} repository tools for context",
                    requestedFiles.size(), requestedTools != null ? requestedTools.size() : 0);

            String fileContext = fetchRequestedContext(owner, repo, baseBranch, requestedFiles, requestedTools);

            // STEP 2: Generate implementation with file context
            log.info("Step 2: Generating implementation for issue #{}", issueNumber);
            String implementationPrompt = promptBuilder.buildImplementationPromptWithContext(
                    issueTitle, issueBody, treeContext, fileContext);

            // Generate implementation with validation and iterative correction
            ImplementationPlan plan = generateValidatedImplementation(
                    session, implementationPrompt, systemPrompt, owner, repo, issueNumber, baseBranch);

            if (plan == null || plan.getFileChanges() == null || plan.getFileChanges().isEmpty()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        """
                                🤖 **AI Agent**: I was unable to generate a valid implementation plan for this issue. \
                                The issue may be too complex or ambiguous for automated implementation.

                                You can mention me in a comment to provide more details or clarification.""");
                return;
            }

            // Enforce max files limit
            if (plan.getFileChanges().size() > agentConfig.getMaxFiles()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: The generated plan requires %d file changes, " +
                                "but the maximum allowed is %d. Please break this issue into smaller tasks.",
                                plan.getFileChanges().size(), agentConfig.getMaxFiles()));
                return;
            }

            // Create branch name
            branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
            sessionService.setBranchName(session, branchName);

            // Create feature branch from base branch
            repositoryClient.createBranch(owner, repo, branchName, baseBranch);
            log.info("Created branch '{}' from '{}' for issue #{}", branchName, baseBranch, issueNumber);

            // Commit file changes and track them in the session
            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                fileChangeApplier.applyFileChange(owner, repo, branchName, change,
                        commitMessage, session, systemPrompt);

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create pull request targeting the base branch
            String prTitle = String.format("AI Agent: %s (fixes #%d)", issueTitle, issueNumber);
            String prBody = promptBuilder.buildPrBody(issueNumber, plan);
            Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                    branchName, baseBranch);

            // Update session with PR number
            sessionService.setPrNumber(session, prNumber);

            // Comment on issue with link to PR
            notificationService.postSuccessComment(owner, repo, issueNumber, plan, prNumber);
            log.info("Successfully created PR #{} for issue #{} in {}", prNumber, issueNumber, repoFullName);

        } catch (Exception e) {
            log.error("Failed to implement issue #{} in {}: {}", issueNumber, repoFullName, e.getMessage(), e);

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);

            // Clean up branch on failure
            if (branchName != null) {
                try {
                    repositoryClient.deleteBranch(owner, repo, branchName);
                } catch (Exception deleteError) {
                    log.warn("Failed to clean up branch '{}': {}", branchName, deleteError.getMessage());
                }
            }

            // Post failure comment
            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("""
                                        🤖 **AI Agent**: Implementation failed with error: `%s`

                                        The created branch has been cleaned up. You can mention me in a comment \
                                        to try again with more details.""",
                                e.getMessage()));
            } catch (Exception commentError) {
                log.error("Failed to post failure comment on issue #{}: {}", issueNumber, commentError.getMessage());
            }
        }
    }

    /**
     * Generates implementation with AI-driven validation using external tools.
     * The AI decides which tools to run for validation and can iterate on errors.
     *
     * @return a valid ImplementationPlan, or null if generation/validation failed
     */
    private ImplementationPlan generateValidatedImplementation(
            AgentSession session, String userMessage, String systemPrompt,
            String owner, String repo, Long issueNumber, String defaultBranch) {

        int maxRetries = agentConfig.getValidation().isEnabled()
                ? agentConfig.getValidation().getMaxRetries()
                : 1;
        int maxFileRequestRounds = 3;
        int maxToolExecutions = agentConfig.getValidation().getMaxToolExecutions();

        // Add available tools info to the initial message
        List<String> availableTools = toolExecutionService.getAvailableTools();
        String toolsInfo = "\n\n**Available repository context tools**: "
                + String.join(", ", toolExecutionService.getAvailableContextTools())
                + "\n**Available validation tools**: " + String.join(", ", availableTools);
        userMessage = userMessage + toolsInfo;

        // Store initial user message in session
        sessionService.addMessage(session, "user", userMessage);

        String currentMessage = userMessage;
        List<AiMessage> conversationHistory = new ArrayList<>();
        int fileRequestRounds = 0;
        int toolExecutions = 0;
        Path workspaceDir = null;
        ImplementationPlan lastValidPlan = null;
        List<String> failedDiffPaths = new ArrayList<>();

        try {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                log.info("Generating implementation for issue #{}, attempt {}/{}", issueNumber, attempt, maxRetries);

                // Call AI to generate implementation
                String aiResponse = aiClient.chat(conversationHistory, currentMessage, systemPrompt, null,
                        agentConfig.getMaxTokens());

                // Store AI response in session
                sessionService.addMessage(session, "assistant", aiResponse);

                // Post the AI's reasoning as a comment (excluding JSON)
                notificationService.postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                // Parse AI response
                ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);
                if (plan == null) {
                    log.warn("Failed to parse implementation plan on attempt {}", attempt);
                    return lastValidPlan;
                }

                // Handle file requests - AI wants to see more files before implementing
                if (plan.hasContextRequests() && !plan.hasFileChanges() && fileRequestRounds < maxFileRequestRounds) {
                    fileRequestRounds++;
                    log.info("AI requesting additional context (files: {}, tools: {}) (round {}/{})",
                            plan.getRequestFiles() != null ? plan.getRequestFiles().size() : 0,
                            plan.getRequestTools() != null ? plan.getRequestTools().size() : 0,
                            fileRequestRounds, maxFileRequestRounds);

                    String fileContext = fetchRequestedContext(owner, repo, defaultBranch,
                            plan.getRequestFiles(), plan.getRequestTools());
                    String filesMessage = "Here is the requested repository context:\n" + fileContext +
                            "\n\nIf you need more context, request additional `requestFiles` or `requestTools`. " +
                            "Otherwise implement the issue and output JSON with fileChanges and runTool for validation.";

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = filesMessage;
                    sessionService.addMessage(session, "user", filesMessage);
                    attempt--;
                    continue;
                }

                // If we have file changes, save them as the last valid plan
                if (plan.hasFileChanges()) {
                    lastValidPlan = plan;
                }

                // If no file changes and no tool request, fail
                if (!plan.hasFileChanges() && !plan.hasToolRequest()) {
                    log.warn("No file changes in implementation plan on attempt {}", attempt);
                    return lastValidPlan;
                }

                // If validation is disabled, return the plan as-is
                if (!agentConfig.getValidation().isEnabled()) {
                    return plan;
                }

                // Handle tool execution for AI-driven validation
                if (plan.hasToolRequest() && plan.hasFileChanges() && toolExecutions < maxToolExecutions) {
                    toolExecutions++;

                    // Prepare workspace if not already done
                    if (workspaceDir == null) {
                        WorkspaceResult workspaceResult = workspaceService.prepareWorkspace(owner, repo,
                                defaultBranch, plan.getFileChanges(), repositoryClient.getCloneUrl(),
                                repositoryClient.getToken());
                        if (!workspaceResult.success()) {
                            log.error("Failed to prepare workspace for validation: {}", workspaceResult.error());
                            repositoryClient.postComment(owner, repo, issueNumber,
                                    "⚠️ **Workspace preparation failed**\n\n" + workspaceResult.error());
                            return plan; // Return plan without validation
                        }
                        workspaceDir = workspaceResult.workspacePath();
                        if (workspaceResult.hasFailedDiffs()) {
                            failedDiffPaths.addAll(workspaceResult.failedDiffs());
                        }
                    } else {
                        // Update existing workspace with new file changes
                        for (FileChange change : plan.getFileChanges()) {
                            try {
                                workspaceService.applyFileChangeToWorkspace(workspaceDir, change);
                            } catch (DiffApplyService.DiffApplyException e) {
                                log.warn("Diff application failed for workspace file {}: {}", change.getPath(), e.getMessage());
                                failedDiffPaths.add(change.getPath());
                            } catch (Exception e) {
                                log.warn("Failed to update workspace file {}: {}", change.getPath(), e.getMessage());
                            }
                        }
                    }

                    // Execute the tool
                    ImplementationPlan.ToolRequest toolRequest = plan.getToolRequest();
                    log.info("AI requested tool execution: {} {}", toolRequest.getTool(),
                            toolRequest.getArgs() != null ? String.join(" ", toolRequest.getArgs()) : "");

                    ToolResult result = toolExecutionService.executeTool(
                            workspaceDir, toolRequest.getTool(), toolRequest.getArgs());

                    // Build feedback message for AI
                    String toolFeedback = promptBuilder.buildToolFeedback(toolRequest, result);

                    // Post tool result as comment
                    notificationService.postToolResultComment(owner, repo, issueNumber, toolRequest, result);

                    // If tool succeeded, we're done
                    if (result.success()) {
                        log.info("Validation tool succeeded on attempt {}", attempt);
                        return plan;
                    }

                    // Tool failed - send feedback to AI for fixing
                    String previousChangesInfo = promptBuilder.buildPreviousChangesInfo(lastValidPlan);
                    String diffFailureInfo = promptBuilder.buildDiffFailureFeedback(failedDiffPaths);
                    String toolFeedbackWithContext = toolFeedback + previousChangesInfo + diffFailureInfo;
                    failedDiffPaths.clear();

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = toolFeedbackWithContext;
                    sessionService.addMessage(session, "user", toolFeedbackWithContext);

                    // Store reference to preserve changes from this iteration
                    ImplementationPlan previousPlan = plan;

                    // Get next response and parse it
                    aiResponse = aiClient.chat(conversationHistory, currentMessage, systemPrompt, null,
                            agentConfig.getMaxTokens());
                    sessionService.addMessage(session, "assistant", aiResponse);
                    notificationService.postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                    plan = responseParser.parseAiResponse(aiResponse);
                    if (plan != null && plan.hasFileChanges()) {
                        // Merge: If AI didn't include all previous files, add the missing ones
                        plan = mergeFileChanges(previousPlan, plan);
                        lastValidPlan = plan;
                    } else if (lastValidPlan != null) {
                        // AI couldn't provide fixes, return last valid plan
                        return lastValidPlan;
                    }
                    continue;
                }

                // No tool request but has file changes - ask AI to provide a validation tool
                if (plan.hasFileChanges() && !plan.hasToolRequest()) {
                    log.info("AI provided file changes without runTool - requesting validation tool");

                    String toolRequestMessage = promptBuilder.buildMissingToolFeedback();

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = toolRequestMessage;
                    sessionService.addMessage(session, "user", toolRequestMessage);
                }
            }

            return lastValidPlan;

        } finally {
            // Clean up workspace
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Executes tool validation loop for follow-up comments.
     * Similar to the validation in generateValidatedImplementation but for comment handling.
     *
     * @return The final valid plan after validation, or null if validation failed
     */
    private ImplementationPlan executeToolValidationLoop(
            AgentSession session, ImplementationPlan plan, String owner, String repo,
            String workingBranch, Long issueNumber, String systemPrompt, String lastAiResponse) {

        int maxToolExecutions = agentConfig.getValidation().getMaxToolExecutions();
        int toolExecutions = 0;
        Path workspaceDir = null;
        ImplementationPlan currentPlan = plan;
        String currentAiResponse = lastAiResponse;
        List<String> failedDiffPaths = new ArrayList<>();

        try {
            while (currentPlan.hasToolRequest() && toolExecutions < maxToolExecutions) {
                toolExecutions++;

                // Prepare workspace if not already done
                if (workspaceDir == null) {
                    WorkspaceResult workspaceResult =
                            workspaceService.prepareWorkspace(owner, repo, workingBranch,
                                    currentPlan.getFileChanges(), repositoryClient.getCloneUrl(),
                                    repositoryClient.getToken());
                    if (!workspaceResult.success()) {
                        log.error("Failed to prepare workspace for validation: {}", workspaceResult.error());
                        repositoryClient.postComment(owner, repo, issueNumber,
                                "⚠️ **Workspace preparation failed**\n\n" + workspaceResult.error());
                        return currentPlan; // Return plan without validation
                    }
                    workspaceDir = workspaceResult.workspacePath();
                    if (workspaceResult.hasFailedDiffs()) {
                        failedDiffPaths.addAll(workspaceResult.failedDiffs());
                    }
                } else {
                    // Update existing workspace with new file changes
                    for (FileChange change : currentPlan.getFileChanges()) {
                        try {
                            workspaceService.applyFileChangeToWorkspace(workspaceDir, change);
                        } catch (DiffApplyService.DiffApplyException e) {
                            log.warn("Diff application failed for workspace file {}: {}", change.getPath(), e.getMessage());
                            failedDiffPaths.add(change.getPath());
                        } catch (Exception e) {
                            log.warn("Failed to update workspace file {}: {}", change.getPath(), e.getMessage());
                        }
                    }
                }

                // Execute the tool
                ImplementationPlan.ToolRequest toolRequest = currentPlan.getToolRequest();
                log.info("Executing validation tool (follow-up): {} {}", toolRequest.getTool(),
                        toolRequest.getArgs() != null ? String.join(" ", toolRequest.getArgs()) : "");

                ToolResult result = toolExecutionService.executeTool(
                        workspaceDir, toolRequest.getTool(), toolRequest.getArgs());

                // Post tool result as comment
                notificationService.postToolResultComment(owner, repo, issueNumber, toolRequest, result);

                // If tool succeeded, we're done
                if (result.success()) {
                    log.info("Validation tool succeeded for follow-up changes");
                    return currentPlan;
                }

                // Tool failed - send feedback to AI for fixing
                String toolFeedback = promptBuilder.buildToolFeedback(toolRequest, result);
                String previousChangesInfo = promptBuilder.buildPreviousChangesInfo(currentPlan);
                String diffFailureInfo = promptBuilder.buildDiffFailureFeedback(failedDiffPaths);
                String feedbackMessage = toolFeedback + previousChangesInfo + diffFailureInfo;
                failedDiffPaths.clear();

                sessionService.addMessage(session, "user", feedbackMessage);

                // Get updated history and call AI
                List<AiMessage> updatedHistory = sessionService.toAiMessages(session);
                currentAiResponse = aiClient.chat(updatedHistory.subList(0, updatedHistory.size() - 1),
                        feedbackMessage, systemPrompt, null, agentConfig.getMaxTokens());
                sessionService.addMessage(session, "assistant", currentAiResponse);

                // Post AI thinking comment
                notificationService.postAiThinkingComment(owner, repo, issueNumber, currentAiResponse);

                // Parse new response and merge with previous changes
                ImplementationPlan previousPlan = currentPlan;
                ImplementationPlan newPlan = responseParser.parseAiResponse(currentAiResponse);
                if (newPlan == null || !newPlan.hasFileChanges()) {
                    log.warn("AI failed to provide file changes after tool failure");
                    return currentPlan; // Return last valid plan
                }

                // Merge: preserve files that weren't updated
                currentPlan = mergeFileChanges(previousPlan, newPlan);
            }

            // Reached max tool executions
            if (toolExecutions >= maxToolExecutions) {
                log.warn("Reached maximum tool executions ({}) for follow-up validation", maxToolExecutions);
            }

            return currentPlan;

        } finally {
            // Clean up workspace
            if (workspaceDir != null) {
                workspaceService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Handles a comment on an issue that mentions the bot.
     * This allows users to request changes or continue work after initial implementation.
     */
    public void handleIssueComment(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long issueNumber = payload.getIssue().getNumber();
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();

        log.info("Handling agent comment #{} on issue #{} in {}", commentId, issueNumber, repoFullName);

        // Look up the session for this issue.
        // For PR comments, the issue number in the payload equals the PR number, so also
        // try a session look-up by PR number when no direct issue session is found.
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

        try {
            // Add reaction to acknowledge
            try {
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            // Update session status
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);

            // Get current branch and default branch
            String branchName = session.getBranchName();
            String defaultBranch = repositoryClient.getDefaultBranch(owner, repo);
            String workingBranch = branchName != null ? branchName : defaultBranch;

            // Build user message - AI already has context from conversation history
            String userMessage = promptBuilder.buildContinuationPrompt(commentBody);

            // Store user message in session
            sessionService.addMessage(session, "user", userMessage);

            // Get conversation history
            List<AiMessage> history = sessionService.toAiMessages(session);

            // Get system prompt
            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);

            // Call AI
            log.info("Requesting AI to continue implementation for issue #{}", issueNumber);
            String aiResponse = aiClient.chat(history.subList(0, history.size() - 1), userMessage,
                    systemPrompt, null, agentConfig.getMaxTokens());

            // Store AI response in session
            sessionService.addMessage(session, "assistant", aiResponse);

            // Post the AI's reasoning as a comment (excluding JSON)
            notificationService.postAiThinkingComment(owner, repo, issueNumber, aiResponse);

            // Parse AI response
            ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);

            // Handle file requests - AI wants to see more files (loop up to 3 rounds)
            int maxFileRequestRounds = 3;
            int fileRequestRounds = 0;
            while (plan != null && plan.hasContextRequests() && fileRequestRounds < maxFileRequestRounds) {
                fileRequestRounds++;
                log.info("AI requesting additional context (files: {}, tools: {}) (round {}/{})",
                        plan.getRequestFiles() != null ? plan.getRequestFiles().size() : 0,
                        plan.getRequestTools() != null ? plan.getRequestTools().size() : 0,
                        fileRequestRounds, maxFileRequestRounds);
                String fileContext = fetchRequestedContext(owner, repo, workingBranch,
                        plan.getRequestFiles(), plan.getRequestTools());

                // Send files and ask AI to continue
                String filesMessage = "Here is the requested repository context:\n" + fileContext +
                        "\n\nIf you need more context, request additional `requestFiles` or `requestTools`. " +
                        "Otherwise continue with the implementation. Output JSON per system prompt format.";
                sessionService.addMessage(session, "user", filesMessage);

                List<AiMessage> updatedHistory = sessionService.toAiMessages(session);
                aiResponse = aiClient.chat(updatedHistory.subList(0, updatedHistory.size() - 1), filesMessage,
                        systemPrompt, null, agentConfig.getMaxTokens());
                sessionService.addMessage(session, "assistant", aiResponse);

                // Post the follow-up AI reasoning as a comment
                notificationService.postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                plan = responseParser.parseAiResponse(aiResponse);
            }

            if (plan == null || !plan.hasFileChanges()) {
                // AI responded but no code changes - the thinking comment was already posted
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                return;
            }

            // Execute tool validation if requested and validation is enabled
            if (agentConfig.getValidation().isEnabled() && plan.hasToolRequest()) {
                plan = executeToolValidationLoop(session, plan, owner, repo, workingBranch,
                        issueNumber, systemPrompt, aiResponse);

                if (plan == null || !plan.hasFileChanges()) {
                    // Validation failed and couldn't be fixed
                    sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                    repositoryClient.postComment(owner, repo, issueNumber,
                            "🤖 **AI Agent**: Validation failed and I couldn't fix the issues. " +
                            "Please check the tool output above and provide more guidance.");
                    return;
                }
            }

            // Apply the new changes
            if (branchName == null) {
                // Create a new branch if we don't have one
                branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
                repositoryClient.createBranch(owner, repo, branchName, defaultBranch);
                sessionService.setBranchName(session, branchName);
            }

            String systemPromptForApply = promptService.getSystemPrompt(AGENT_PROMPT_NAME);
            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d, follow-up)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                fileChangeApplier.applyFileChange(owner, repo, branchName, change,
                        commitMessage, session, systemPromptForApply);

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create PR if we don't have one yet
            if (session.getPrNumber() == null) {
                String prTitle = String.format("AI Agent: %s (fixes #%d)", session.getIssueTitle(), issueNumber);
                String prBody = promptBuilder.buildPrBody(issueNumber, plan);
                Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                        branchName, defaultBranch);
                sessionService.setPrNumber(session, prNumber);
            }

            // Update session status
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);

            // Post success comment
            notificationService.postFollowUpSuccessComment(owner, repo, issueNumber,
                    plan, session.getPrNumber());
            log.info("Successfully applied follow-up changes for issue #{}", issueNumber);

        } catch (Exception e) {
            log.error("Failed to handle comment on issue #{}: {}", issueNumber, e.getMessage(), e);

            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("""
                                        🤖 **AI Agent**: Failed to process your request: `%s`

                                        Please try again or provide more details.""",
                                e.getMessage()));
            } catch (Exception commentError) {
                log.error("Failed to post error comment on issue #{}: {}", issueNumber, commentError.getMessage());
            }

            // Restore previous status if we were updating
            if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
                sessionService.setStatus(session,
                        session.getPrNumber() != null ? AgentSession.AgentSessionStatus.PR_CREATED
                                                      : AgentSession.AgentSessionStatus.FAILED);
            }
        }
    }

    /**
     * Merges file changes from two plans.
     * If the new plan doesn't include a file from the previous plan, that file is preserved.
     * If both plans have the same file, the new version takes precedence.
     */
    private ImplementationPlan mergeFileChanges(ImplementationPlan previousPlan, ImplementationPlan newPlan) {
        if (previousPlan == null || !previousPlan.hasFileChanges()) {
            return newPlan;
        }
        if (newPlan == null || !newPlan.hasFileChanges()) {
            return previousPlan;
        }

        // Build a map of file paths to changes from the new plan
        Map<String, FileChange> newChangesMap = new HashMap<>();
        for (FileChange fc : newPlan.getFileChanges()) {
            newChangesMap.put(fc.getPath(), fc);
        }

        // Add missing files from previous plan
        List<FileChange> mergedChanges = new ArrayList<>(newPlan.getFileChanges());
        for (FileChange fc : previousPlan.getFileChanges()) {
            if (!newChangesMap.containsKey(fc.getPath())) {
                log.info("Preserving file change from previous plan: {}", fc.getPath());
                mergedChanges.add(fc);
            }
        }

        return ImplementationPlan.builder()
                .summary(newPlan.getSummary() != null ? newPlan.getSummary() : previousPlan.getSummary())
                .fileChanges(mergedChanges)
                .toolRequest(newPlan.getToolRequest())
                .requestFiles(newPlan.getRequestFiles())
                .requestTools(newPlan.getRequestTools())
                .build();
    }

    /**
     * Fetches any additional repository context requested by the AI.
     */
    private String fetchRequestedContext(String owner, String repo, String ref,
                                         List<String> filePaths,
                                         List<ImplementationPlan.ToolRequest> toolRequests) {
        StringBuilder sb = new StringBuilder();

        if (filePaths != null && !filePaths.isEmpty()) {
            sb.append("## Requested Files\n");
            sb.append(fetchSpecificFiles(owner, repo, ref, filePaths));
        }

        String toolOutput = executeRequestedContextTools(owner, repo, ref, toolRequests);
        if (!toolOutput.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("## Repository Tool Results\n");
            sb.append(toolOutput);
        }

        if (sb.isEmpty()) {
            return "No additional repository context could be retrieved.";
        }
        return sb.toString();
    }

    private String executeRequestedContextTools(String owner, String repo, String ref,
                                                List<ImplementationPlan.ToolRequest> toolRequests) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return "";
        }

        WorkspaceResult workspaceResult = workspaceService.prepareWorkspace(owner, repo, ref, List.of(),
                repositoryClient.getCloneUrl(), repositoryClient.getToken());
        if (!workspaceResult.success()) {
            return "Unable to prepare repository workspace for context tools: " + workspaceResult.error();
        }

        Path workspaceDir = workspaceResult.workspacePath();
        try {
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
                    sb.append(result.output().isBlank() ? "(no output)" : result.output()).append("\n\n");
                } else {
                    sb.append("Failed: ").append(result.error().isBlank() ? result.output() : result.error())
                            .append("\n\n");
                }
            }
            return sb.toString().strip();
        } finally {
            workspaceService.cleanupWorkspace(workspaceDir);
        }
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
}
