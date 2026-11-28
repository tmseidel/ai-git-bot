package org.remus.giteabot.learning.context;

import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;

import java.util.List;

/**
 * What lesson retrieval may know about one run. The scope fields locate lessons exactly — Git
 * integration, bot, repository and workflow — and nothing else widens it: the same repository
 * coordinates under another integration or bot select nothing.
 *
 * @param workflowKey         the workflow asking; only {@code agentic-review} receives lessons
 * @param changedPaths        repository-relative paths the change touches, for {@code paths:} scopes
 * @param relevanceText       title/description of the change, matched against lesson tags
 * @param settings            the run's effective learning settings
 * @param contextWindowTokens the model's context window, bounding the guidance allowance
 */
public record LessonSelectionRequest(Long gitIntegrationId, Long botId, String repoOwner,
                                     String repoName, String workflowKey,
                                     List<String> changedPaths, String relevanceText,
                                     AgentReviewLearningSettings settings,
                                     int contextWindowTokens) {

    public LessonSelectionRequest {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        relevanceText = relevanceText == null ? "" : relevanceText;
        settings = settings == null ? AgentReviewLearningSettings.DISABLED : settings;
    }
}
