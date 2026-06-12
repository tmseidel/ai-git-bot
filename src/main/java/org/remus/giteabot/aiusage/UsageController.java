package org.remus.giteabot.aiusage;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Admin page that audits AI usage: token consumption per AI interaction and
 * a log of AI errors (e.g. HTTP 401 responses), both filterable by timespan,
 * sortable and paginated.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UsageController {

    private final AiUsageService aiUsageService;

    @GetMapping("/usage")
    public String usage(@RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(defaultValue = "0") int usagePage,
                        @RequestParam(defaultValue = "timestamp") String usageSort,
                        @RequestParam(defaultValue = "desc") String usageDir,
                        @RequestParam(defaultValue = "0") int errorPage,
                        @RequestParam(defaultValue = "timestamp") String errorSort,
                        @RequestParam(defaultValue = "desc") String errorDir,
                        Model model) {
        Instant fromInstant = parseFrom(from);
        Instant toInstant = parseTo(to);

        Page<AiUsageLog> usage = aiUsageService.findUsage(fromInstant, toInstant,
                Math.max(0, usagePage), usageSort, "asc".equalsIgnoreCase(usageDir));
        Page<AiErrorLog> errors = aiUsageService.findErrors(fromInstant, toInstant,
                Math.max(0, errorPage), errorSort, "asc".equalsIgnoreCase(errorDir));

        model.addAttribute("usagePage", usage);
        model.addAttribute("errorPage", errors);
        model.addAttribute("from", from != null ? from : "");
        model.addAttribute("to", to != null ? to : "");
        model.addAttribute("usageSort", usageSort);
        model.addAttribute("usageDir", usageDir);
        model.addAttribute("errorSort", errorSort);
        model.addAttribute("errorDir", errorDir);
        model.addAttribute("activeNav", "usage");
        return "usage/list";
    }

    /**
     * Removes all recorded AI usage entries.
     */
    @PostMapping("/usage/clear")
    public String clearUsage(RedirectAttributes redirectAttributes) {
        aiUsageService.clearUsage();
        redirectAttributes.addFlashAttribute("success", "All AI usage entries have been cleared");
        return "redirect:/usage";
    }

    /**
     * Exports the error log (filtered by the same timespan as the page) as a
     * downloadable JSON document.  The response is streamed incrementally —
     * rows are fetched in pages and written directly to the HTTP output stream
     * so that heap usage stays bounded regardless of the result set size.
     */
    @GetMapping("/usage/errors/export")
    public void exportErrors(@RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"ai-error-log.json\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        aiUsageService.exportErrors(parseFrom(from), parseTo(to), response.getOutputStream());
    }

    private static Instant parseFrom(String from) {
        LocalDate date = parseDate(from);
        return date != null ? date.atStartOfDay(ZoneId.systemDefault()).toInstant() : null;
    }

    private static Instant parseTo(String to) {
        LocalDate date = parseDate(to);
        return date != null ? date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant() : null;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            log.debug("Ignoring invalid date filter '{}'", value);
            return null;
        }
    }
}
