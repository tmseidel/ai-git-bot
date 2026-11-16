package org.remus.giteabot.workflowrun;

import java.util.List;

/**
 * The choices of the Workflow runs page's dropdown filters: only values that have runs.
 *
 * @param projects      repositories as {@code owner/name}
 * @param workflowTypes workflow keys
 * @param bots          bots by id and name
 */
public record FilterOptions(List<String> projects, List<String> workflowTypes, List<BotOption> bots) {
}
