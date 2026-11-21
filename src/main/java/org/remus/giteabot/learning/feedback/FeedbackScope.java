package org.remus.giteabot.learning.feedback;

/**
 * The scope a feedback command is resolved in. Every lookup is narrowed by all five components, so
 * a public reference alone never reaches another bot's, host's, repository's or pull request's data.
 */
public record FeedbackScope(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                            Long prNumber) {

    public boolean matches(org.remus.giteabot.review.structured.WorkflowOutput output) {
        return output != null
                && eq(gitIntegrationId, output.getGitIntegrationId())
                && eq(botId, output.getBotId())
                && equalsIgnoreCase(repoOwner, output.getRepoOwner())
                && equalsIgnoreCase(repoName, output.getRepoName())
                && eq(prNumber, output.getPrNumber());
    }

    private static boolean eq(Long a, Long b) {
        return a != null && a.equals(b);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
