package org.remus.giteabot.prworkflow.readmesync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and dispatches the {@code @bot regenerate-readme [instruction]} slash
 * command for the {@link ReadmeSyncWorkflow}.
 *
 * <p>Mirrors {@code E2eTestSlashCommandHandler} / {@code UnitTestSlashCommandHandler}:
 * it acknowledges the comment with a 👀 reaction, hydrates the PR details when
 * the webhook payload only carries an issue block, then re-triggers the
 * {@code readme-sync} workflow via the orchestrator, threading any trailing
 * free-text as the {@link PrWorkflowContext#HINT_README_SYNC_GUIDANCE} hint.
 * The command is ignored (returns {@code false}) when the bot does not have the
 * workflow enabled, so the regular comment handling still runs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadmeSyncSlashCommandHandler {

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+regenerate-readme\\b\\s*(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    private final GiteaClientFactory repositoryClientFactory;

    /**
     * @return {@code true} when the comment was recognized as the
     *         {@code regenerate-readme} slash command and dispatched;
     *         {@code false} otherwise.
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
        String guidance = matcher.group(1) == null ? "" : matcher.group(1).trim();

        if (!isEnabledOnBot(bot)) {
            log.info("[Bot '{}'] readme-sync slash command ignored — workflow not enabled", bot.getName());
            return false;
        }

        addEyesReaction(bot, payload);
        log.info("[Bot '{}'] readme-sync slash command detected (guidance='{}'), dispatching {} workflow",
                bot.getName(), abbreviate(guidance, 80), ReadmeSyncWorkflow.KEY);
        try {
            hydratePullRequest(bot, payload);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Could not hydrate PR for regenerate-readme: {}", bot.getName(), e.getMessage());
        }
        try {
            Map<String, String> hints = guidance.isBlank()
                    ? Map.of()
                    : Map.of(PrWorkflowContext.HINT_README_SYNC_GUIDANCE, guidance);
            orchestrator.run(bot, payload, ReadmeSyncWorkflow.KEY, hints);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] readme-sync slash command dispatch failed: {}",
                    bot.getName(), e.getMessage(), e);
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
                    .contains(ReadmeSyncWorkflow.KEY);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] readme-sync enabled-check failed: {}", bot.getName(), e.getMessage());
            return false;
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
