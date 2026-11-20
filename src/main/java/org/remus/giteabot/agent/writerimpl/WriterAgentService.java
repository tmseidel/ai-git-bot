package org.remus.giteabot.agent.writerimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.AgentErrorNotificationService;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.loop.AgentBudget;
import org.remus.giteabot.agent.loop.AgentLoop;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

@Slf4j
public class WriterAgentService {

    private static final String WRITER_PROMPT_NAME = "writer";
    /**
     * Default values, overridable via {@code AgentConfigProperties.writer.*}. They
     * are kept as constants so unit tests that construct the service without a
     * fully-populated config still get the historical behaviour.
     */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    private static final int DEFAULT_MAX_INITIAL_TREE_FILES = 100;
    private static final int DEFAULT_MAX_DISCUSSION_CHARS = 20_000;
    private static final int DEFAULT_MAX_SINGLE_DISCUSSION_COMMENT_CHARS = 4_000;

    private final RepositoryPlatform repositoryPlatform;
    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final WorkspaceService workspaceService;
    private final String writerAgentSystemPrompt;
    private final McpToolCatalog mcpToolCatalog;
    private final AgentErrorNotificationService errorNotificationService;
    private final BranchSwitcher branchSwitcher;
    private final AgentToolRouter toolRouter;
    private final ToolCatalog toolCatalog;
    private final java.util.Set<String> allowedBuiltinTools;
    private final WriterPromptBuilder promptBuilder = new WriterPromptBuilder();
    private final WriterResponseParser responseParser = new WriterResponseParser();
    private final SystemPromptAssembler systemPromptAssembler = new SystemPromptAssembler();
    private final int contextWindowTokens;
    /**
     * The bot's user whitelist: whose issue comments the writer takes into account, both in the
     * discussion of an assignment run and as follow-up answers. Accepts everyone when the
     * whitelist is empty.
     */
    private final Predicate<String> commenterAllowed;

    public WriterAgentService(RepositoryPlatform repositoryPlatform,
                              AiClient aiClient,
                              PromptService promptService,
                              AgentConfigProperties agentConfig,
                              AgentSessionService sessionService,
                              ToolExecutionService toolExecutionService,
                              ToolCatalog toolCatalog,
                              WorkspaceService workspaceService,
                              String writerAgentSystemPrompt,
                              McpOrchestrationService mcpOrchestrationService,
                              McpConfiguration mcpConfiguration,
                              McpToolCatalog mcpToolCatalog,
                              java.util.Set<String> allowedBuiltinTools,
                              int contextWindowTokens,
                              PythonExecutionService pythonExecution,
                              Predicate<String> commenterAllowed) {
        this.repositoryPlatform = repositoryPlatform;
        this.repositoryClient = repositoryPlatform.getClient();
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.workspaceService = workspaceService;
        this.writerAgentSystemPrompt = writerAgentSystemPrompt;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.toolCatalog = toolCatalog;
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.errorNotificationService = new AgentErrorNotificationService(this.repositoryClient);
        this.branchSwitcher = new BranchSwitcher(toolExecutionService);
        this.toolRouter = new AgentToolRouter(toolExecutionService, toolCatalog, mcpOrchestrationService,
                mcpConfiguration, this.mcpToolCatalog, this.repositoryClient, allowedBuiltinTools,
                pythonExecution);
        this.contextWindowTokens = contextWindowTokens;
        this.commenterAllowed = commenterAllowed != null ? commenterAllowed : username -> true;
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();
        String issueRef = normalizeBranchRef(payload.getIssue().getRef());

        BotIdentity bot = new BotIdentity(repositoryPlatform.getUser(owner));
        if (!bot.isIssueAssignee(payload)) {
            log.debug("Ignoring writer assignment for issue #{} because assignee does not match bot '{}'",
                    issueNumber, bot.account().username());
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
        try {
            session = sessionService.createSession(owner, repo, issueNumber, issueTitle,
                    AgentSession.AgentSessionType.WRITER, issueAuthor);
            sessionService.setBranchName(session, baseBranch);
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);
            repositoryClient.postIssueComment(owner, repo, issueNumber,
                    "🤖 **AI Technical Writer**: I've been assigned and will review this issue for completeness.");

            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    repositoryPlatform, owner, repo, baseBranch, null);
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            try (Workspace workspace = wsResult.workspace()) {
                String treeContext = promptBuilder.buildTreeContext(
                        repositoryClient.getRepositoryTree(owner, repo, baseBranch), maxInitialTreeFiles());
                String discussionContext = promptBuilder.buildDiscussionContext(
                        fetchDiscussion(owner, repo, issueNumber, bot),
                        maxDiscussionChars(), maxSingleDiscussionCommentChars());
                runWriterLoop(session, owner, repo, issueNumber, workspace, baseBranch,
                        promptBuilder.buildInitialPrompt(issueNumber, issueTitle, issueBody, treeContext,
                                discussionContext));
            }
        } catch (DataIntegrityViolationException e) {
            log.info("Writer session was created concurrently for issue #{} in {}/{}", issueNumber, owner, repo);
        } catch (Exception e) {
            log.error("Writer failed while handling assignment for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.FAILED, e);
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
        String commenter = acceptedCommenter(owner, payload);
        if (commenter == null) {
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
        // Compact persisted history before starting a new writer run to prevent
        // unbounded growth across follow-up sessions. Rebind to the returned
        // managed entity: our reference's messages collection still points at the
        // rows compaction deleted, which would break the loop with
        // ObjectNotFoundException.
        session = sessionService.compactContextWindow(session.getId());
        try {
            String baseBranch = resolveBaseBranch(owner, repo, payload, session);
            WorkspaceResult wsResult = workspaceService.prepareWorkspace(
                    repositoryPlatform, owner, repo, baseBranch, null);
            if (!wsResult.success()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postIssueComment(owner, repo, issueNumber,
                        "⚠️ **AI Technical Writer**: Failed to prepare read-only repository context: "
                                + wsResult.error());
                return;
            }
            try (Workspace workspace = wsResult.workspace()) {
                runWriterLoop(session, owner, repo, issueNumber, workspace, baseBranch,
                        promptBuilder.buildContinuationPrompt(commenter, payload.getComment().getBody()));
            }
        } catch (Exception e) {
            log.error("Writer failed while handling follow-up for issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage(), e);
            handleWriterFailure(session, owner, repo, issueNumber,
                    AgentSession.AgentSessionStatus.IN_PROGRESS, e);
        }
    }

    private void runWriterLoop(AgentSession session, String owner, String repo,
                               Long issueNumber, Workspace workspace, String baseBranch, String userMessage) {
        WriterAgentStrategy strategy = new WriterAgentStrategy(
                resolveWriterSystemPrompt(),
                promptBuilder,
                responseParser,
                sessionService,
                repositoryClient,
                branchSwitcher,
                toolRouter,
                mcpToolCatalog,
                toolCatalog,
                allowedBuiltinTools,
                maxToolRounds());
        // The historic loop ran for-each `round in 0..maxToolRounds` (inclusive), i.e. one extra
        // iteration beyond the context-round limit so the AI gets a chance to produce a
        // terminal answer after exhausting context. Two extra iterations are needed: round
        // maxToolRounds + 1 delivers the wrap-up instruction (its tool calls are answered
        // with synthetic results), round maxToolRounds + 2 is the model's answer.
        // maxContextRounds stays the repository-context budget the strategy enforces.
        AgentBudget budget = new AgentBudget(
                loopMaxRounds(maxToolRounds()), maxToolRounds(), 0, agentConfig.getBudget().getMaxTokensPerCall(),
                agentConfig.getBudget().getMaxToolResultChars(), agentConfig.getBudget().getMaxHistoryChars(),
                contextWindowTokens, agentConfig.getBudget().getProactiveCompactionThreshold());
        AgentLoop loop = new AgentLoop(aiClient, sessionService, budget);
        AgentRunContext ctx = AgentRunContext.forIssue(
                session, owner, repo, issueNumber, workspace, baseBranch);
        loop.run(ctx, userMessage + "\n\n" + outputContract(), strategy);
    }

    private String normalizeBranchRef(String ref) {
        return BranchRefs.normalize(ref);
    }



    /**
     * The username of the commenter whose follow-up continues the writer session, or
     * {@code null} when the comment is ignored. Anyone on the bot's user whitelist may answer
     * the writer's questions (everyone when the whitelist is empty), not only the issue author:
     * on an issue the writer created itself the author is the bot. The bot's own comments
     * (its status messages and questions) never count as an answer.
     */
    private String acceptedCommenter(String owner, WebhookPayload payload) {
        WebhookPayload.Owner user = payload.getComment() != null ? payload.getComment().getUser() : null;
        String commenter = user != null ? user.getLogin() : null;
        if (commenter == null || commenter.isBlank()) {
            log.debug("Ignoring writer follow-up without a commenter on issue #{}", payload.getIssue().getNumber());
            return null;
        }
        BotIdentity bot = new BotIdentity(repositoryPlatform.getUser(owner));
        if (bot.is(user) || commenter.equalsIgnoreCase(bot.account().username())) {
            log.debug("Ignoring the bot's own comment on writer issue #{}", payload.getIssue().getNumber());
            return null;
        }
        if (!commenterAllowed.test(commenter)) {
            log.debug("Ignoring writer follow-up from '{}' on issue #{}: not on the bot's user whitelist",
                    commenter, payload.getIssue().getNumber());
            return null;
        }
        return commenter;
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

    /**
     * The human discussion on the issue, oldest first, for the assignment prompt: skips the bot's
     * own comments (its status messages and questions), comments from users outside the bot's
     * whitelist, GitLab system notes and empty comments. Best effort: a provider that cannot
     * list comments leaves the discussion out instead of failing the run.
     */
    private List<WriterPromptBuilder.DiscussionComment> fetchDiscussion(String owner, String repo, Long issueNumber,
                                                                        BotIdentity bot) {
        List<Map<String, Object>> comments;
        try {
            comments = repositoryClient.getIssueComments(owner, repo, issueNumber);
        } catch (Exception e) {
            log.warn("Failed to fetch comments of issue #{} in {}/{} for the writer prompt, continuing without them: {}",
                    issueNumber, owner, repo, e.getMessage());
            return List.of();
        }
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        int skippedBot = 0;
        int skippedNotWhitelisted = 0;
        for (Map<String, Object> comment : comments) {
            if (comment == null || Boolean.TRUE.equals(comment.get("system"))
                    || extractCommentBody(comment).isBlank()) {
                continue;
            }
            Map<?, ?> actor = commentActor(comment);
            if (isBot(actor, bot)) {
                skippedBot++;
                continue;
            }
            if (!commenterAllowed.test(actor != null ? extractCommenterName(actor) : null)) {
                skippedNotWhitelisted++;
                continue;
            }
            kept.add(comment);
        }
        log.info("Writer discussion for issue #{} in {}/{}: {} of {} comments kept ({} by the bot, {} not whitelisted)",
                issueNumber, owner, repo, kept.size(), comments.size(), skippedBot, skippedNotWhitelisted);
        return sortChronologically(kept).stream()
                .map(comment -> {
                    Map<?, ?> actor = commentActor(comment);
                    return new WriterPromptBuilder.DiscussionComment(
                            actor != null ? extractCommenterName(actor) : null,
                            extractCommentBody(comment),
                            extractCommentTimestamp(comment));
                })
                .toList();
    }

    /**
     * Sorts by creation time. Providers differ in the order they list comments (GitLab returns
     * notes newest first), so the timestamps decide; when one cannot be parsed, the provider's
     * order is kept.
     */
    private List<Map<String, Object>> sortChronologically(List<Map<String, Object>> comments) {
        List<Instant> instants = new ArrayList<>();
        for (Map<String, Object> comment : comments) {
            Instant instant = parseInstant(extractCommentTimestamp(comment));
            if (instant == null) {
                return comments;
            }
            instants.add(instant);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(instants::get));
        return order.stream().map(comments::get).toList();
    }

    private static Instant parseInstant(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The author of a comment: {@code user} on Gitea, GitHub and Bitbucket, {@code author} on GitLab. */
    private static Map<?, ?> commentActor(Map<String, Object> comment) {
        for (String key : List.of("user", "author")) {
            if (comment.get(key) instanceof Map<?, ?> actor) {
                return actor;
            }
        }
        return null;
    }

    /**
     * Whether the bot wrote the comment: matched by id ({@code account_id} on Bitbucket) and, for
     * providers whose comment payloads carry no id, by username.
     */
    private static boolean isBot(Map<?, ?> actor, BotIdentity bot) {
        if (actor == null) {
            return false;
        }
        for (String key : List.of("account_id", "id")) {
            String id = WebhookPayload.Owner.of(actor.get(key), null).getId();
            if (id != null) {
                return bot.hasId(id);
            }
        }
        String name = extractCommenterName(actor);
        return name != null && name.equalsIgnoreCase(bot.account().username());
    }

    /**
     * The commenter's username as the whitelist knows it: {@code login} (Gitea, GitHub),
     * {@code username} (GitLab) or {@code nickname} (Bitbucket).
     */
    private static String extractCommenterName(Map<?, ?> actor) {
        for (String key : List.of("login", "username", "nickname")) {
            Object value = actor.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String extractCommentBody(Map<String, Object> comment) {
        if (comment.get("body") instanceof String text) {
            return text;
        }
        if (comment.get("content") instanceof Map<?, ?> content && content.get("raw") instanceof String raw) {
            return raw;
        }
        return "";
    }

    private static String extractCommentTimestamp(Map<String, Object> comment) {
        for (String key : List.of("created_at", "created_on")) {
            if (comment.get(key) instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String resolveBaseBranch(String owner, String repo, WebhookPayload payload, AgentSession session) {
        if (session.getBranchName() != null && !session.getBranchName().isBlank()) {
            return session.getBranchName();
        }
        String issueRef = payload.getIssue() != null ? normalizeBranchRef(payload.getIssue().getRef()) : null;
        return issueRef != null && !issueRef.isBlank() ? issueRef : repositoryClient.getDefaultBranch(owner, repo);
    }

    private String resolveWriterSystemPrompt() {
        String basePrompt;
        if (writerAgentSystemPrompt != null && !writerAgentSystemPrompt.isBlank()) {
            basePrompt = writerAgentSystemPrompt;
        } else {
            basePrompt = promptService.getSystemPrompt(WRITER_PROMPT_NAME);
        }
        // Drive the prompt mode purely off the configured client (i.e. the operator's
        // `use_legacy_tool_calling` toggle). The writer strategy advertises native
        // function-calling whenever the client supports it, so the prompt and the
        // transport stay in sync.
        ToolingMode mode = (aiClient != null && aiClient.supportsNativeTools())
                ? ToolingMode.NATIVE : ToolingMode.LEGACY;
        return systemPromptAssembler.assemble(basePrompt, toolCatalog, allowedBuiltinTools,
                mcpToolCatalog, mode,
                org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind.WRITER_AGENT);
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
                Search-tool args use the shape [pattern, path?, flags?]. Common flags like -i, -n, -l and --include=*.java are supported.
                `find` supports both [glob, path?] and shell-like forms such as ["src/main/java", "-name", "*.java"].
                For alternation in rg/ripgrep/grep patterns, use `|` (not `\\|`).
                You may use requestFiles or read-only repository requestTools when existing issue or repository context is needed before asking or finalizing.
                Do not request repository write tools, file mutation tools, or build/validation tools.
                If critical information is missing, set readyToCreate=false and include clarifyingQuestions.
                If no critical questions remain, set readyToCreate=true and include revisedIssueDraft.
                You have %d repository-context rounds in total (`agent.writer.max-tool-rounds`). Prefer the few
                files that matter most and keep your final answer for the last round.
                """.formatted(maxToolRounds());
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

    /**
     * Rounds the loop gets for this run: {@code maxToolRounds} repository-context
     * rounds, plus the wrap-up round that carries the instruction and the round
     * after it that produces the answer (see {@link WriterAgentStrategy}'s policy).
     */
    static int loopMaxRounds(int maxToolRounds) {
        return maxToolRounds + 2;
    }

    private int maxToolRounds() {
        if (agentConfig == null || agentConfig.getWriter() == null) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
        int configured = agentConfig.getWriter().getMaxToolRounds();
        return configured > 0 ? configured : DEFAULT_MAX_TOOL_ROUNDS;
    }

    private int maxInitialTreeFiles() {
        if (agentConfig == null || agentConfig.getWriter() == null) {
            return DEFAULT_MAX_INITIAL_TREE_FILES;
        }
        int configured = agentConfig.getWriter().getMaxInitialTreeFiles();
        return configured > 0 ? configured : DEFAULT_MAX_INITIAL_TREE_FILES;
    }

    /** Size cap of the discussion section, shared with the coding agent's {@code agent.context.max-issue-comments-chars}. */
    private int maxDiscussionChars() {
        AgentConfigProperties.ContextConfig context = agentConfig != null ? agentConfig.getContext() : null;
        int configured = context != null ? context.getMaxIssueCommentsChars() : 0;
        return configured > 0 ? configured : DEFAULT_MAX_DISCUSSION_CHARS;
    }

    /** Per-comment cap, shared with the coding agent's {@code agent.context.max-single-issue-comment-chars}. */
    private int maxSingleDiscussionCommentChars() {
        AgentConfigProperties.ContextConfig context = agentConfig != null ? agentConfig.getContext() : null;
        int configured = context != null ? context.getMaxSingleIssueCommentChars() : 0;
        return configured > 0 ? configured : DEFAULT_MAX_SINGLE_DISCUSSION_COMMENT_CHARS;
    }
}
