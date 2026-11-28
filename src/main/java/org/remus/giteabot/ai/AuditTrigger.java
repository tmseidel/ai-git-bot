package org.remus.giteabot.ai;

/**
 * What triggered a workflow run: the bot (with the names of its integrations at trigger
 * time) and the pull request or issue it works on. Names are captured as plain strings so
 * the run stays readable after the bot or an integration is deleted.
 *
 * @param botId              id of the bot
 * @param botName            name of the bot at trigger time
 * @param aiIntegrationName  name of the bot's AI integration at trigger time
 * @param gitIntegrationName name of the bot's Git integration at trigger time
 * @param repoOwner          repository owner login, or {@code null} when the payload has none
 * @param repoName           repository name, or {@code null} when the payload has none
 * @param triggerKind        what the run works on, or {@code null} when the payload does not say
 * @param triggerNumber      pull request or issue number, or {@code null}
 */
public record AuditTrigger(Long botId,
                           String botName,
                           String aiIntegrationName,
                           String gitIntegrationName,
                           String repoOwner,
                           String repoName,
                           TriggerKind triggerKind,
                           Long triggerNumber) {
}
