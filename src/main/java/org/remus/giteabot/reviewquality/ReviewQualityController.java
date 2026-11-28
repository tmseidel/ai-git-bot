package org.remus.giteabot.reviewquality;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * The Review quality page: what agentic reviews find, whether it gets fixed, whether reviewers
 * agree, and what it costs. One tab per section; filters, tables and paging work as on the Reviews
 * and Workflow runs pages. The only write is the assumptions form of the Cost tab.
 */
@Controller
@RequiredArgsConstructor
public class ReviewQualityController {

    static final String BASE = "/review-quality";

    private final ReviewQualityService service;
    private final ReviewQualitySettingsService settingsService;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @GetMapping(BASE)
    public String overview(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        ReviewQualityViews.OverviewView view = service.overview(filter, locale());
        common(model, "overview", view.charts());
        model.addAttribute("view", view);
        return "review-quality/overview";
    }

    @GetMapping(BASE + "/prs")
    public String prs(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        ReviewQualityViews.PrsView view = service.prs(filter, locale());
        common(model, "prs", view.charts());
        model.addAttribute("view", view);
        return "review-quality/prs";
    }

    @GetMapping(BASE + "/repositories")
    public String repositories(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        common(model, "repositories", Map.of());
        model.addAttribute("view", service.repositories(filter, locale()));
        return "review-quality/repositories";
    }

    @GetMapping(BASE + "/authors")
    public String authors(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        common(model, "authors", Map.of());
        model.addAttribute("view", service.authors(filter, locale()));
        return "review-quality/authors";
    }

    @GetMapping(BASE + "/topics")
    public String topics(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        ReviewQualityViews.TopicsView view = service.topics(filter, locale());
        common(model, "topics", view.charts());
        model.addAttribute("view", view);
        return "review-quality/topics";
    }

    @GetMapping(BASE + "/accuracy")
    public String accuracy(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        ReviewQualityViews.AccuracyView view = service.accuracy(filter, locale());
        common(model, "accuracy", view.charts());
        model.addAttribute("view", view);
        return "review-quality/accuracy";
    }

    @GetMapping(BASE + "/cost")
    public String cost(@ModelAttribute("filter") ReviewQualityFilterForm filter, Model model) {
        ReviewQualityViews.CostView view = service.cost(filter, locale());
        common(model, "cost", view.charts());
        model.addAttribute("view", view);
        return "review-quality/cost";
    }

    /** Stores the assumptions of the estimated return; measured figures never change with them. */
    @PostMapping(BASE + "/assumptions")
    public String saveAssumptions(@RequestParam(required = false) Integer minutesBlocker,
                                  @RequestParam(required = false) Integer minutesMedium,
                                  @RequestParam(required = false) Integer minutesLow,
                                  @RequestParam(required = false) Integer minutesIncorrect,
                                  @RequestParam(required = false) BigDecimal hourlyRate,
                                  @RequestParam(required = false) String currency,
                                  @RequestParam(required = false) String returnQuery,
                                  Authentication admin, RedirectAttributes redirect) {
        try {
            if (minutesBlocker == null || minutesMedium == null || minutesLow == null || minutesIncorrect == null) {
                throw new IllegalArgumentException("missing value");
            }
            settingsService.save(minutesBlocker, minutesMedium, minutesLow, minutesIncorrect, hourlyRate, currency,
                    admin == null ? null : "admin:" + admin.getName());
            redirect.addFlashAttribute("success", messageSource.getMessage("reviewQuality.assumptions.saved", null, locale()));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", messageSource.getMessage("reviewQuality.assumptions.invalid", null,
                    locale()));
        }
        return "redirect:" + BASE + "/cost" + safeQuery(returnQuery);
    }

    private void common(Model model, String tab, Map<String, Object> charts) {
        model.addAttribute("activeNav", "reviewQuality");
        model.addAttribute("tab", tab);
        model.addAttribute("bots", service.botOptions());
        model.addAttribute("projects", service.projectOptions());
        model.addAttribute("chartsJson", scriptSafe(objectMapper.writeValueAsString(charts)));
        model.addAttribute("minSample", service.minSample());
    }

    /**
     * JSON placed inside a {@code <script>} element: labels carry free text (finding categories), so
     * nothing in it may close the element or start markup.
     */
    static String scriptSafe(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    /** Only a plain query string of this page's own filter is carried back. */
    static String safeQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 1000
                || !query.matches("[A-Za-z0-9_.~%+=&/-]*")) {
            return "";
        }
        return "?" + query;
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }
}
