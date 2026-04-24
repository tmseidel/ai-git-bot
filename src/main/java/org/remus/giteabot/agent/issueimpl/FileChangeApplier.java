package org.remus.giteabot.agent.issueimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;

/**
 * Applies file changes (CREATE / UPDATE / DELETE) to a repository branch.
 * <p>
 * For diff-based updates that fail, the service asks the AI to regenerate the
 * complete file content so the change can still be committed.
 */
@Slf4j
public class FileChangeApplier {

    private final RepositoryApiClient repositoryClient;
    private final DiffApplyService diffApplyService;
    private final AiClient aiClient;
    private final AgentSessionService sessionService;
    private final AgentConfigProperties agentConfig;

    public FileChangeApplier(RepositoryApiClient repositoryClient,
                             DiffApplyService diffApplyService,
                             AiClient aiClient,
                             AgentSessionService sessionService,
                             AgentConfigProperties agentConfig) {
        this.repositoryClient = repositoryClient;
        this.diffApplyService = diffApplyService;
        this.aiClient = aiClient;
        this.sessionService = sessionService;
        this.agentConfig = agentConfig;
    }

    /**
     * Applies a single file change to the given branch, with diff retry support.
     *
     * @param owner         Repository owner
     * @param repo          Repository name
     * @param branchName    Target branch
     * @param change        The file change to apply
     * @param commitMessage Commit message
     * @param session       Agent session (for AI conversation context during retry)
     * @param systemPrompt  System prompt (for AI retry)
     */
    public void applyFileChange(String owner, String repo, String branchName,
                                FileChange change, String commitMessage,
                                AgentSession session, String systemPrompt) {
        switch (change.getOperation()) {
            case CREATE -> repositoryClient.createOrUpdateFile(owner, repo, change.getPath(),
                    change.getContent(), commitMessage, branchName, null);
            case UPDATE -> {
                String sha = repositoryClient.getFileSha(owner, repo, change.getPath(), branchName);
                String newContent;

                if (change.isDiffBased()) {
                    // Apply diff to existing content
                    String originalContent = repositoryClient.getFileContent(owner, repo,
                            change.getPath(), branchName);
                    try {
                        newContent = diffApplyService.applyDiff(originalContent, change.getDiff());
                    } catch (DiffApplyService.DiffApplyException e) {
                        // If we have AI context, try to regenerate the diff
                        if (aiClient != null && session != null && systemPrompt != null) {
                            log.warn("Diff failed for {}, asking AI to regenerate with current file content", change.getPath());

                            String regeneratedContent = askAiToRegenerateDiff(
                                    systemPrompt, session, change.getPath(),
                                    change.getDiff(), originalContent);

                            if (regeneratedContent != null) {
                                newContent = regeneratedContent;
                                log.info("AI successfully regenerated content for {}", change.getPath());
                            } else {
                                throw new DiffApplyService.DiffApplyException(
                                        "Failed to apply diff to file `" + change.getPath() + "` and AI could not regenerate: " + e.getMessage());
                            }
                        } else {
                            throw new DiffApplyService.DiffApplyException(
                                    "Failed to apply diff to file `" + change.getPath() + "`: " + e.getMessage());
                        }
                    }
                    log.debug("Applied diff to {}: {} chars -> {} chars",
                            change.getPath(), originalContent.length(), newContent.length());
                } else {
                    // Full content replacement
                    newContent = change.getContent();
                }

                repositoryClient.createOrUpdateFile(owner, repo, change.getPath(),
                        newContent, commitMessage, branchName, sha);
            }
            case DELETE -> {
                String sha = repositoryClient.getFileSha(owner, repo, change.getPath(), branchName);
                repositoryClient.deleteFile(owner, repo, change.getPath(),
                        commitMessage, branchName, sha);
            }
        }
    }

    /**
     * Asks the AI to regenerate a diff when the original diff failed to apply.
     * Provides the current file content so the AI can create a correct replacement.
     *
     * @param systemPrompt   System prompt
     * @param session        Current session
     * @param filePath       Path of the file
     * @param failedDiff     The diff that failed to apply
     * @param currentContent Current content of the file
     * @return The new file content, or null if regeneration failed
     */
    private String askAiToRegenerateDiff(String systemPrompt, AgentSession session,
                                          String filePath, String failedDiff,
                                          String currentContent) {
        try {
            String prompt = String.format("""
                    ## Diff Application Failed
                    
                    The diff I tried to apply to `%s` failed because the file content has changed.
                    
                    ### Current File Content:
                    ```
                    %s
                    ```
                    
                    ### The Diff That Failed:
                    ```
                    %s
                    ```
                    
                    Please provide the **complete new file content** that implements the intended change.
                    Output ONLY the file content, no JSON, no markdown code blocks, just the raw file content.
                    """, filePath,
                    currentContent.length() > 5000 ? currentContent.substring(0, 5000) + "\n... (truncated)" : currentContent,
                    failedDiff);

            List<AiMessage> history = sessionService.toAiMessages(session);
            String response = aiClient.chat(history, prompt, systemPrompt, null, agentConfig.getMaxTokens());

            if (response == null || response.isBlank()) {
                return null;
            }

            // Clean up the response - remove any markdown code blocks if present
            String content = response.strip();
            if (content.startsWith("```")) {
                // Remove first line (```java or similar)
                int firstNewline = content.indexOf('\n');
                if (firstNewline > 0) {
                    content = content.substring(firstNewline + 1);
                }
                // Remove closing ```
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3).stripTrailing();
                }
            }

            // Validate that we got something reasonable
            if (content.length() < 10) {
                log.warn("AI returned very short content for {}: {} chars", filePath, content.length());
                return null;
            }

            return content;

        } catch (Exception e) {
            log.error("Failed to ask AI to regenerate diff for {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}

