package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.aiusage.AiUsageService;
import org.remus.giteabot.workflowrun.WorkflowRunQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Controller
public class DashboardController {

    /** Window of the "AI Errors" card. */
    static final Duration AI_ERROR_WINDOW = Duration.ofDays(7);

    /** Rows of the "Recent workflow runs" card. */
    static final int RECENT_RUNS = 5;

    private final BotService botService;
    private final AiUsageService aiUsageService;
    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final WorkflowRunQueryService workflowRunQueryService;

    public DashboardController(BotService botService, AiUsageService aiUsageService,
                               RepositoryPlatformFactory repositoryPlatformFactory,
                               WorkflowRunQueryService workflowRunQueryService) {
        this.botService = botService;
        this.aiUsageService = aiUsageService;
        this.repositoryPlatformFactory = repositoryPlatformFactory;
        this.workflowRunQueryService = workflowRunQueryService;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        List<Bot> bots = botService.findAll();
        model.addAttribute("bots", bots);
        model.addAttribute("gitUsernames",
                repositoryPlatformFactory.findCachedUsernames(bots.stream().map(Bot::getGitIntegration).toList()));
        model.addAttribute("totalBots", bots.size());
        model.addAttribute("activeBots", bots.stream().filter(Bot::isEnabled).count());
        model.addAttribute("totalWebhookCalls", bots.stream().mapToLong(Bot::getWebhookCallCount).sum());
        model.addAttribute("totalTokensSent", aiUsageService.totalInputTokens());
        model.addAttribute("totalTokensReceived", aiUsageService.totalOutputTokens());
        Instant aiErrorsSince = Instant.now().minus(AI_ERROR_WINDOW);
        model.addAttribute("aiErrorCount", aiUsageService.countErrorsSince(aiErrorsSince));
        // The card links to the Workflow runs page filtered to the same window. That filter is
        // a datetime-local value, here in the server's zone, and applies to the run's start,
        // which is close enough to the start of the failed call.
        ZoneId zone = ZoneId.systemDefault();
        model.addAttribute("aiErrorsFrom", LocalDateTime.ofInstant(aiErrorsSince, zone)
                .truncatedTo(ChronoUnit.MINUTES).toString());
        // The zone goes along, or the page's filter form would re-read "from" in the browser's zone.
        model.addAttribute("aiErrorsZone", zone.getId());
        model.addAttribute("recentRuns", workflowRunQueryService.latestRuns(RECENT_RUNS));
        model.addAttribute("activeNav", "dashboard");
        return "dashboard";
    }
}
