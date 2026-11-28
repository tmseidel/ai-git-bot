package org.remus.giteabot.ai;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AiRetryProperties;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Decorator around an {@link AiClient} that retries provider-side overload
 * failures (HTTP 503/529, {@code "status": "UNAVAILABLE"}, {@code overloaded},
 * "high demand") with exponential backoff and jitter, and reports every
 * scheduled retry to a {@link ProviderRetryNotifier} so the affected pull
 * request or issue learns when the next attempt happens.
 *
 * <p>It also feeds the audit log, because it is the one place that sees both levels: each call
 * through it is one logical AI call, which starts the next round of the current {@link AuditRun}
 * ({@link AiAuditContext#beginCall()}), and each request it sends to the provider, retries
 * included, is one attempt reported to the {@link AiAuditRecorder} with its outcome.</p>
 *
 * <p>The wait happens in place on the calling thread. Workflow webhooks run on
 * virtual threads, so a bounded backoff does not occupy an OS thread; the
 * affected run simply takes longer. Every other failure — authentication,
 * prompt-too-long, malformed payloads — is rethrown immediately, because those
 * are handled (or not retried) elsewhere.</p>
 */
@Slf4j
public class RetryAiClient implements AiClient {

    /** Injectable wait so tests can assert the backoff without sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * -- GETTER --
     *  Returns the wrapped provider-specific client.
     */
    @Getter
    private final AiClient delegate;

    private final AiRetryProperties properties;
    private final ProviderRetryNotifier notifier;
    private final AiAuditRecorder recorder;
    private final Sleeper sleeper;
    private final Random random;

    public RetryAiClient(AiClient delegate, AiRetryProperties properties,
                         ProviderRetryNotifier notifier, AiAuditRecorder recorder) {
        this(delegate, properties, notifier, recorder, Thread::sleep, new Random());
    }

    RetryAiClient(AiClient delegate, AiRetryProperties properties, ProviderRetryNotifier notifier,
                  AiAuditRecorder recorder, Sleeper sleeper, Random random) {
        this.delegate = delegate;
        this.properties = properties;
        this.notifier = notifier;
        this.recorder = recorder;
        this.sleeper = sleeper;
        this.random = random;
    }

    @Override
    public String submitReviewPrompt(String systemPrompt, String modelOverride, String userMessage) {
        return withRetry(() -> delegate.submitReviewPrompt(systemPrompt, modelOverride, userMessage), result -> null);
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride) {
        return withRetry(() -> delegate.chat(conversationHistory, newUserMessage,
                systemPrompt, modelOverride), result -> null);
    }

    @Override
    public String chat(List<AiMessage> conversationHistory, String newUserMessage,
                       String systemPrompt, String modelOverride, Integer maxTokensOverride) {
        return withRetry(() -> delegate.chat(conversationHistory, newUserMessage,
                systemPrompt, modelOverride, maxTokensOverride), result -> null);
    }

    @Override
    public ChatTurn chatWithTools(List<AiMessage> conversationHistory,
                                  String newUserMessage,
                                  List<ToolDescriptor> tools,
                                  String systemPrompt,
                                  String modelOverride,
                                  Integer maxTokensOverride) {
        return withRetry(() -> delegate.chatWithTools(conversationHistory, newUserMessage,
                tools, systemPrompt, modelOverride, maxTokensOverride), ChatTurn::stopReason);
    }

    @Override
    public boolean supportsNativeTools() {
        return delegate.supportsNativeTools();
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        return delegate.isPromptTooLongError(e);
    }

    @Override
    public boolean isProviderUnavailableError(Throwable error) {
        return delegate.isProviderUnavailableError(error);
    }

    @Override
    public String getModel() {
        return delegate.getModel();
    }

    /**
     * One logical call: starts the next audit round, then sends the request, repeating it while
     * the provider is unavailable. Every request is reported as an attempt.
     *
     * @param stopReason why the model stopped, read from a successful result; {@code null} for
     *                   text-only calls that do not expose it
     */
    private <T> T withRetry(Supplier<T> call, Function<T, StopReason> stopReason) {
        AiAuditContext.beginCall();
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        List<Duration> plannedDelays = plannedDelays(maxAttempts);
        for (int attempt = 1; ; attempt++) {
            recorder.attemptStarted();
            try {
                T result = call.get();
                recorder.attemptSucceeded(result != null ? stopReason.apply(result) : null);
                return result;
            } catch (RuntimeException e) {
                recorder.attemptFailed(e);
                if (!properties.isEnabled() || !delegate.isProviderUnavailableError(e)) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    log.warn("AI provider unavailable — giving up after {} attempt(s){}: {}",
                            attempt, labelSuffix(), e.getMessage());
                    notifier.retriesExhausted(new ProviderRetryNotifier.Event(attempt, maxAttempts,
                            null, null, plannedDelays, e));
                    throw e;
                }
                Duration delay = withJitter(plannedDelays.get(attempt - 1));
                Instant nextAttemptAt = Instant.now().plus(delay);
                log.warn("AI provider unavailable — attempt {}/{} failed{}, retrying at {} (in {}s): {}",
                        attempt, maxAttempts, labelSuffix(), nextAttemptAt, delay.toSeconds(), e.getMessage());
                notifier.retryScheduled(new ProviderRetryNotifier.Event(attempt, maxAttempts,
                        delay, nextAttemptAt, plannedDelays, e));
                try {
                    sleeper.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Un-jittered backoff plan: {@code initialDelay * multiplier^attempt}, capped at {@code maxDelay}. */
    List<Duration> plannedDelays(int maxAttempts) {
        List<Duration> delays = new ArrayList<>(Math.max(0, maxAttempts - 1));
        for (int i = 1; i < maxAttempts; i++) {
            double grown = properties.getInitialDelay().toMillis() * Math.pow(properties.getMultiplier(), i - 1.0);
            delays.add(Duration.ofMillis((long) Math.min(grown, properties.getMaxDelay().toMillis())));
        }
        return delays;
    }

    private Duration withJitter(Duration base) {
        double jitter = Math.max(0.0, Math.min(1.0, properties.getJitter()));
        if (jitter == 0.0) {
            return base;
        }
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter;
        return Duration.ofMillis(Math.max(0L, (long) (base.toMillis() * factor)));
    }

    private static String labelSuffix() {
        AiRetryContext.Notice notice = AiRetryContext.notice();
        return notice == null ? "" : " (workflow '" + notice.label() + "')";
    }
}
