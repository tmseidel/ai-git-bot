package org.remus.giteabot.reviewui;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The Reviews pages: agentic reviews, the feedback given on them, and the lessons drawn from that
 * feedback — with the curation actions of the Git commands ({@link ReviewUiActionService}).
 * Every action is a CSRF-protected POST that redirects back with a flash message.
 */
@Controller
@RequiredArgsConstructor
public class ReviewUiController {

    private static final String REVIEW_REF = "{reference:O[0-9]+}";
    private static final String FEEDBACK_REF = "{reference:FB[0-9]+}";
    private static final String LESSON_REF = "{reference:L[0-9]+}";

    private final ReviewUiQueryService queryService;
    private final ReviewUiActionService actionService;
    private final MessageSource messageSource;

    @GetMapping("/reviews")
    public String reviews(@ModelAttribute("filter") ReviewUiFilterForm filter, Model model) {
        common(model, "reviews");
        model.addAttribute("reviews", queryService.reviews(filter));
        return "reviews/list";
    }

    @GetMapping("/reviews/" + REVIEW_REF)
    public String review(@PathVariable String reference, Model model) {
        common(model, "reviews");
        model.addAttribute("review", queryService.review(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "reviews/detail";
    }

    @GetMapping("/reviews/feedback")
    public String feedback(@ModelAttribute("filter") ReviewUiFilterForm filter, Model model) {
        common(model, "feedback");
        model.addAttribute("feedback", queryService.feedback(filter));
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "reviews/feedback";
    }

    @GetMapping("/reviews/lessons")
    public String lessons(@ModelAttribute("filter") ReviewUiFilterForm filter, Model model) {
        common(model, "lessons");
        model.addAttribute("lessons", queryService.lessons(filter));
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "reviews/lessons";
    }

    @GetMapping("/reviews/lessons/" + LESSON_REF)
    public String lesson(@PathVariable String reference, Model model) {
        common(model, "lessons");
        model.addAttribute("lesson", queryService.lesson(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "reviews/lesson";
    }

    // ------------------------------------------------------------------ actions

    @PostMapping("/reviews/feedback/" + FEEDBACK_REF + "/retract")
    public String retract(@PathVariable String reference, @RequestParam(required = false) String returnTo,
                          Authentication admin, RedirectAttributes redirect) {
        return finish(actionService.retract(reference, nameOf(admin)), returnTo, "/reviews/feedback",
                redirect);
    }

    @PostMapping("/reviews/feedback/" + FEEDBACK_REF + "/propose")
    public String propose(@PathVariable String reference, @RequestParam(required = false) String text,
                          @RequestParam(required = false) String paths,
                          @RequestParam(required = false) String requestId,
                          @RequestParam(required = false) String returnTo,
                          Authentication admin, RedirectAttributes redirect) {
        ReviewUiActionService.Result result = actionService.propose(reference, text, paths, requestId,
                nameOf(admin));
        String lesson = result.success() ? String.valueOf(result.args()[0]).split("@")[0] : null;
        return finish(result, lesson != null ? "/reviews/lessons/" + lesson : returnTo,
                "/reviews/feedback", redirect);
    }

    @PostMapping("/reviews/lessons/" + LESSON_REF + "/activate")
    public String activate(@PathVariable String reference, @RequestParam int revision,
                           @RequestParam(required = false) String requestId,
                           @RequestParam(required = false) String returnTo,
                           Authentication admin, RedirectAttributes redirect) {
        return finish(actionService.activate(reference, revision, requestId, nameOf(admin)), returnTo,
                lessonPage(reference), redirect);
    }

    @PostMapping("/reviews/lessons/" + LESSON_REF + "/reject")
    public String reject(@PathVariable String reference, @RequestParam int revision,
                         @RequestParam(required = false) String requestId,
                         @RequestParam(required = false) String returnTo,
                         Authentication admin, RedirectAttributes redirect) {
        return finish(actionService.reject(reference, revision, requestId, nameOf(admin)), returnTo,
                lessonPage(reference), redirect);
    }

    @PostMapping("/reviews/lessons/" + LESSON_REF + "/revise")
    public String revise(@PathVariable String reference, @RequestParam(required = false) String text,
                         @RequestParam(required = false) String paths,
                         @RequestParam(required = false) String requestId,
                         Authentication admin, RedirectAttributes redirect) {
        return finish(actionService.revise(reference, text, paths, requestId, nameOf(admin)), null,
                lessonPage(reference), redirect);
    }

    @PostMapping("/reviews/lessons/" + LESSON_REF + "/disable")
    public String disable(@PathVariable String reference, @RequestParam(required = false) String reason,
                          @RequestParam(required = false) String requestId,
                          @RequestParam(required = false) String returnTo,
                          Authentication admin, RedirectAttributes redirect) {
        return finish(actionService.disable(reference, reason, requestId, nameOf(admin)), returnTo,
                lessonPage(reference), redirect);
    }

    // ------------------------------------------------------------------ helpers

    private void common(Model model, String tab) {
        model.addAttribute("activeNav", "reviews");
        model.addAttribute("tab", tab);
        model.addAttribute("bots", queryService.botOptions());
        model.addAttribute("projects", queryService.projectOptions());
    }

    private String finish(ReviewUiActionService.Result result, String returnTo, String fallback,
                          RedirectAttributes redirect) {
        redirect.addFlashAttribute(result.success() ? "success" : "error",
                messageSource.getMessage(result.messageKey(), result.args(), LocaleContextHolder.getLocale()));
        return "redirect:" + safeReturn(returnTo, fallback);
    }

    /** Only paths of the Reviews pages are followed — never an absolute or foreign URL. */
    static String safeReturn(String returnTo, String fallback) {
        if (returnTo != null && returnTo.startsWith("/reviews") && !returnTo.startsWith("//")
                && !returnTo.contains("\\") && returnTo.chars().noneMatch(Character::isISOControl)) {
            return returnTo;
        }
        return fallback;
    }

    private static String lessonPage(String reference) {
        return "/reviews/lessons/" + reference;
    }

    private static String nameOf(Authentication admin) {
        return admin == null ? null : admin.getName();
    }
}
