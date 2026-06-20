package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    /**
     * Whether the issue implementation agent feature is enabled.
     */
    private boolean enabled = true;

    /**
     * Maximum number of files the agent can modify in a single implementation.
     */
    private int maxFiles = 10;


    /**
     * Whitelist of repositories (in "owner/repo" format) where the agent is active.
     * If empty, the agent is active on all repositories.
     */
    private List<String> allowedRepos = List.of();

    /**
     * Prefix for branches created by the agent.
     */
    private String branchPrefix = "ai-agent/";

    /**
     * Maximum characters of file content to include in prompts.
     * Lower values are needed for local models with smaller context windows.
     * Default: 100000 (suitable for cloud providers like Claude/GPT-4)
     * For local models with 16k context: use ~20000
     */
    private int maxFileContentChars = 100000;

    /**
     * Validation settings.
     */
    private ValidationConfig validation = new ValidationConfig();

    /**
     * Context-size settings for prompts built during issue implementation.
     */
    private ContextConfig context = new ContextConfig();

    /**
     * Writer-agent specific settings.
     */
    private WriterConfig writer = new WriterConfig();

    /**
     * JSON-Schema validation settings for agent plan responses (Step 5).
     */
    private SchemaConfig schema = new SchemaConfig();

    /**
     * Step 7.2 — consolidated budget knobs for the agent loop. Replaces the
     * scattered counters that were previously sprinkled across
     * {@link ValidationConfig}, {@link WriterConfig} and hard-coded constants
     * in the implementation services.
     */
    private BudgetConfig budget = new BudgetConfig();

    /**
     * Step 7.3 — optional Critic / Reflection step. Disabled by default; when
     * enabled, runs an extra LLM call after successful validation to review
     * whether the diff actually addresses the issue.
     */
    private CriticConfig critic = new CriticConfig();


    @Data
    public static class SchemaConfig {
        /**
         * If {@code true}, parsers reject AI responses that fail JSON-Schema
         * validation. If {@code false} (default), violations are only logged
         * and counted via the {@code agent.plan.schema_violations_total}
         * Micrometer counter while the existing repair heuristics continue to
         * run.
         */
        private boolean enforce = false;
    }

    @Data
    public static class ContextConfig {
        /**
         * Maximum number of repository-tree file entries included in agent prompts.
         */
        private int maxTreeFiles = 500;

        /**
         * Maximum number of issue comments included in agent prompts.
         */
        private int maxIssueComments = 50;

        /**
         * Maximum total characters used for issue-comment context.
         */
        private int maxIssueCommentsChars = 20_000;

        /**
         * Maximum characters included from a single issue comment.
         */
        private int maxSingleIssueCommentChars = 4_000;
    }

    @Data
    public static class WriterConfig {
        /**
         * Maximum number of context-collection rounds the writer agent will run
         * before giving up and asking the user for more input.
         */
        private int maxToolRounds = 5;

        /**
         * Maximum number of repository-tree file entries used in the writer's
         * initial prompt.
         */
        private int maxInitialTreeFiles = 100;
    }

    /**
     * Step 7.2 — single source of truth for all numeric agent-loop budgets.
     */
    @Data
    public static class BudgetConfig {
        /**
         * Hard upper bound on chat-decide-act iterations of the
         * {@link org.remus.giteabot.agent.loop.AgentLoop AgentLoop}. Strategies
         * may apply tighter sub-budgets internally but cannot exceed this cap.
         */
        private int maxRounds = 10;

        /**
         * Maximum number of pure context-fetch rounds (the AI may ask for more
         * files / tool output without spending an implementation attempt).
         * Replaces the previously hard-coded {@code fileRequestRounds < 3}
         * literal in the coding strategy.
         */
        private int maxContextRounds = 3;

        /**
         * Maximum number of validation/correction iterations.
         */
        private int maxValidationRetries = 3;

        /**
         * Maximum number of context-tool requests (e.g. {@code ls},
         * {@code cat}) that {@code IssueImplementationService} will execute
         * within a single AI round. Replaces the previously hard-coded
         * {@code MAX_CONTEXT_TOOL_REQUESTS = 5} literal.
         */
        private int maxContextToolRequestsPerRound = 5;

        /**
         * Token budget passed to {@code aiClient.chat / chatWithTools} for
         * every AI call. Mirrors the legacy {@code agent.max-tokens} setting.
         */
        private int maxTokensPerCall = 16384;

        /**
         * Maximum characters retained from a single tool result in the
         * in-memory history. This field is currently unused — individual
         * tool results are kept at full size; all tool messages are truncated
         * together when the context window budget is exceeded.
         * Default: 8_000.
         */
        private int maxToolResultChars = 8_000;

        /**
         * Character budget for the in-memory history list during a single
         * AgentLoop run. When exceeded, the HistoryCompactor prunes older
         * tool-pair groups and replaces them with a summary. Default: 120_000.
         */
        private int maxHistoryChars = 120_000;

        /**
         * Fraction (0.0-1.0) of the model's context window at which proactive
         * compaction triggers. When token usage reaches this threshold, the
         * TokenUsageTracker compacts the history before the next AI call.
         * Default: 0.7 (70%).
         */
        private double proactiveCompactionThreshold = 0.7;
    }

    /**
     * Step 7.3 — Critic / Reflection step configuration.
     */
    @Data
    public static class CriticConfig {
        /**
         * Default {@code false}: the critic step is opt-in. When disabled,
         * the loop short-circuits with an APPROVE outcome and never makes
         * an extra LLM call.
         */
        private boolean enabled = false;

        /**
         * Maximum number of additional Plan/Critique iterations triggered by
         * an {@code ITERATE} verdict. Each iteration counts towards the loop's
         * {@link BudgetConfig#getMaxRounds()} cap, so this is a soft limit.
         */
        private int maxIterations = 1;

        /**
         * Optional triggers; if non-empty, the critic only runs when one of
         * the listed conditions matches. Currently informational — strategies
         * may evaluate it themselves. Supported tokens: {@code LARGE_DIFF}.
         */
        private List<String> requireApprovalFor = List.of();
    }

    @Data
    public static class ValidationConfig {
        public enum NonValidationFailurePolicy {
            STRICT,
            IGNORE_MCP_AFTER_VALIDATION_SUCCESS
        }

        /**
         * Whether to validate generated code before committing.
         */
        private boolean enabled = true;

        /**
         * Maximum number of tool executions per validation cycle.
         * Prevents infinite loops if AI keeps requesting tools.
         */
        private int maxToolExecutions = 10;

        /**
         * Timeout in seconds for tool commands.
         */
        private int toolTimeoutSeconds = 300;

        /**
         * Policy for handling failures of non-validation tools in runTools.
         * STRICT: any non-validation failure requires a retry.
         * IGNORE_MCP_AFTER_VALIDATION_SUCCESS: ignore MCP tool failures when all validation tools passed.
         */
        private NonValidationFailurePolicy nonValidationFailurePolicy =
                NonValidationFailurePolicy.IGNORE_MCP_AFTER_VALIDATION_SUCCESS;

        /**
         * List of available build/validation tools the AI can use.
         * These tools must be installed in the Docker image.
         */
        private List<String> availableTools = List.of(
                "mvn",      // Java/Maven
                "gradle",   // Java/Gradle
                "npm",      // JavaScript/TypeScript
                "go",       // Go
                "cargo",    // Rust
                "python3",  // Python
                "pip",      // Python packages
                "make",     // C/C++ and general builds
                "gcc",      // C compiler
                "g++",      // C++ compiler
                "ruby",     // Ruby
                "bundle",   // Ruby Bundler
                "dotnet"    // .NET
        );
    }
}
