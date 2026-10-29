package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.aiusage.AiUsageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Controller
public class DashboardController {

    private final BotService botService;
    private final AiUsageService aiUsageService;
    private final GitUserService gitUserService;

    public DashboardController(BotService botService, AiUsageService aiUsageService, GitUserService gitUserService) {
        this.botService = botService;
        this.aiUsageService = aiUsageService;
        this.gitUserService = gitUserService;
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
                gitUserService.findCachedUsernames(bots.stream().map(Bot::getGitIntegration).toList()));
        model.addAttribute("totalBots", bots.size());
        model.addAttribute("activeBots", bots.stream().filter(Bot::isEnabled).count());
        model.addAttribute("totalWebhookCalls", bots.stream().mapToLong(Bot::getWebhookCallCount).sum());
        model.addAttribute("totalTokensSent", aiUsageService.totalInputTokens());
        model.addAttribute("totalTokensReceived", aiUsageService.totalOutputTokens());
        model.addAttribute("aiErrorCount",
                aiUsageService.countErrorsSince(Instant.now().minus(Duration.ofDays(7))));
        model.addAttribute("activeNav", "dashboard");
        return "dashboard";
    }
}
