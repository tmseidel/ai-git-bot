package org.remus.giteabot.prworkflow.unittest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and dispatches the {@code @bot generate-tests} /
 * {@code @bot rerun-unit-tests} slash commands for the {@link UnitTestWorkflow}.
 *
 * <p>Mirrors {@code E2eTestSlashCommandHandler}: it acknowledges the comment
 * with a 👀 reaction, hydrates the PR details when the webhook payload only
 * carries an issue block, then re-triggers the {@code unit-test-author}
 * workflow via the orchestrator. The command is ignored (returns
 * {@code false}) when the bot does not have the workflow enabled, so the
 * regular comment handling still runs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitTestSlashCommandHandler {

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(generate-tests?|rerun-unit-tests?)\\b\\s*(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    private final GiteaClientFactory repositoryClientFactory;

    /**
     * @return {@code true} when the comment was recognized as a unit-test
     *         slash command and dispatched; {@code false} otherwise.
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload) {
        if (bot == null || payload == null || payload.getComment() == null) {
            return false;
        }
        String body = payload.getComment().getBody();
        if (body == null || body.isBlank()) {
            return false;
        }
        Matcher matcher = COMMAND_PATTERN.matcher(body);
        if (!matcher.find()) {
            return false;
        }
        String verb = matcher.group(1).toLowerCase(Locale.ROOT);

        if (!isEnabledOnBot(bot)) {
            log.info("[Bot '{}'] unit-test slash command '{}' ignored — workflow not enabled",
                    bot.getName(), verb);
            return false;
        }

        addEyesReaction(bot, payload);
        log.info("[Bot '{}'] unit-test slash command '{}' detected, dispatching {} workflow",
                bot.getName(), verb, UnitTestWorkflow.KEY);
        try {
            hydratePullRequest(bot, payload);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Could not hydrate PR for '{}': {}", bot.getName(), verb, e.getMessage());
        }
        try {
            orchestrator.run(bot, payload, UnitTestWorkflow.KEY, Map.of());
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] unit-test slash command '{}' dispatch failed: {}",
                    bot.getName(), verb, e.getMessage(), e);
        }
        return true;
    }

    private boolean isEnabledOnBot(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return false;
        }
        try {
            return selectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .contains(UnitTestWorkflow.KEY);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] unit-test enabled-check failed: {}", bot.getName(), e.getMessage());
            return false;
        }
    }

    private void addEyesReaction(Bot bot, WebhookPayload payload) {
        if (payload.getComment() == null || payload.getComment().getId() == null
                || payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long commentId = payload.getComment().getId();
        try {
            RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
            client.addReaction(owner, repo, commentId, "eyes");
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to add 👀 reaction to comment #{}: {}",
                    bot.getName(), commentId, e.getMessage());
        }
    }

    private Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        return payload.getNumber();
    }

    @SuppressWarnings("unchecked")
    private void hydratePullRequest(Bot bot, WebhookPayload payload) {
        if (payload.getPullRequest() != null
                && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank()) {
            return;
        }
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = resolvePrNumber(payload);
        if (prNumber == null || prNumber <= 0) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
        Map<String, Object> pr = client.getPullRequestDetails(owner, repo, prNumber);
        if (pr == null || pr.isEmpty()) {
            return;
        }
        WebhookPayload.PullRequest target = payload.getPullRequest();
        if (target == null) {
            target = new WebhookPayload.PullRequest();
            payload.setPullRequest(target);
        }
        target.setNumber(prNumber);
        if (pr.get("title") instanceof String t) target.setTitle(t);
        if (pr.get("body") instanceof String b) target.setBody(b);
        if (pr.get("state") instanceof String s) target.setState(s);
        Map<String, Object> head = pr.get("head") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (head != null) {
            WebhookPayload.Head h = new WebhookPayload.Head();
            if (head.get("ref") instanceof String r) h.setRef(r);
            if (head.get("sha") instanceof String s) h.setSha(s);
            target.setHead(h);
        }
        Map<String, Object> base = pr.get("base") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (base != null) {
            WebhookPayload.Head b = new WebhookPayload.Head();
            if (base.get("ref") instanceof String r) b.setRef(r);
            if (base.get("sha") instanceof String s) b.setSha(s);
            target.setBase(b);
        }
    }
}

