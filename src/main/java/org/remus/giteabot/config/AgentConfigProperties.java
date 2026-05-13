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
     * Maximum tokens for AI responses during issue implementation.
     * This is typically higher than the default for code reviews since
     * implementation responses include full file contents.
     */
    private int maxTokens = 16384;

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
         * Maximum number of validation/correction iterations.
         * If code fails validation, it will be sent back to AI for fixes up to this many times.
         */
        private int maxRetries = 3;

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
                "bundle"    // Ruby Bundler
        );
    }
}
