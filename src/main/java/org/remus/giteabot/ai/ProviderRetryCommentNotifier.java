package org.remus.giteabot.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AiRetryProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Default {@link ProviderRetryNotifier}: posts the retry schedule (and the
 * final exhaustion report) as a comment on the pull request or issue that the
 * running workflow belongs to.
 *
 * <p>Commenting is best-effort — a failing comment API never affects the
 * retry. Without an {@link AiRetryContext} notice on the thread, notices are
 * only logged at debug level by {@link RetryAiClient}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderRetryCommentNotifier implements ProviderRetryNotifier {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private static final int MAX_ERROR_CHARS = 500;

    private final AiRetryProperties properties;

    @Override
    public void retryScheduled(Event event) {
        AiRetryContext.State state = AiRetryContext.state();
        if (state == null || !state.claimScheduledNotice(properties.getNoticeCooldown(), Instant.now())) {
            return;
        }
        post(state.notice, scheduledBody(state.notice, event));
    }

    @Override
    public void retriesExhausted(Event event) {
        AiRetryContext.State state = AiRetryContext.state();
        if (state == null || !state.claimExhaustedNotice()) {
            return;
        }
        post(state.notice, exhaustedBody(state.notice, event));
    }

    private void post(AiRetryContext.Notice notice, String body) {
        try {
            notice.sink().post(body);
        } catch (RuntimeException e) {
            log.warn("Failed to post provider-retry notice for workflow '{}': {}",
                    notice.label(), e.getMessage());
        }
    }

    private String scheduledBody(AiRetryContext.Notice notice, Event event) {
        Instant next = event.nextAttemptAt();
        return """
                ⏳ **AI Git Bot — automatic retry scheduled**

                The AI provider is temporarily unavailable, so this workflow is paused while the bot retries.

                - Next attempt **%d of %d** at **%s** (in %ds)
                - Backoff between attempts: %s
                - Provider error: `%s`

                _Workflow: `%s`_
                """.formatted(
                event.attempt() + 1, event.maxAttempts(), TIMESTAMP.format(next),
                delaySeconds(event.delay()), schedule(event.plannedDelays()),
                snippet(event.error()), notice.label());
    }

    private String exhaustedBody(AiRetryContext.Notice notice, Event event) {
        return """
                ⚠️ **AI Git Bot — automatic retries exhausted**

                The AI provider stayed unavailable across **%d attempts** (backoff: %s). Last failure at **%s**.

                ```
                %s
                ```

                No further retry is scheduled. The next possible retry is once the provider is healthy again
                **and** this workflow is triggered anew — push an update or mention the bot. Provider overload
                spikes usually clear within a few minutes.

                _Workflow: `%s`_
                """.formatted(
                event.attempt(), schedule(event.plannedDelays()),
                TIMESTAMP.format(Instant.now()), snippet(event.error()), notice.label());
    }

    /**
     * The wait {@link RetryAiClient} actually sleeps for this attempt. Taken from the
     * event instead of recomputed from the clock, so a slow comment API can never make
     * the notice promise a shorter pause than the one that is really scheduled.
     */
    private static long delaySeconds(Duration delay) {
        return delay == null ? 0 : Math.max(0, delay.toSeconds());
    }

    /** Renders the backoff plan, e.g. {@code 10s → 20s → 40s → 60s}. */
    static String schedule(List<Duration> delays) {
        if (delays == null || delays.isEmpty()) {
            return "(single attempt)";
        }
        return delays.stream().map(ProviderRetryCommentNotifier::human)
                .reduce((a, b) -> a + " → " + b).orElse("");
    }

    private static String human(Duration delay) {
        long seconds = delay.toSeconds();
        if (seconds > 0 && seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    /** Single-line, Markdown-safe excerpt of the provider error. */
    static String snippet(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        String flat = error.getMessage().replace('\n', ' ').replace('\r', ' ').replace('`', '\'').strip();
        return flat.length() <= MAX_ERROR_CHARS ? flat : flat.substring(0, MAX_ERROR_CHARS - 1) + "…";
    }
}
