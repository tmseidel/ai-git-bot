package org.remus.giteabot.ai;

import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Ensures the {@link AiAuditContext} thread-local never leaks between async
 * tasks: pooled executor threads are reused, so the session id set by one
 * webhook handler must not bleed into the next task running on that thread.
 *
 * <p>Registered as a {@code @Component} and explicitly wired into the
 * application's {@code ThreadPoolTaskExecutor} by
 * {@link org.remus.giteabot.config.AsyncConfig}. Spring Boot does
 * <strong>not</strong> auto-discover {@link TaskDecorator} beans, so the
 * explicit registration is required.</p>
 */
@Component
public class AiAuditContextClearingTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } finally {
                AiAuditContext.clear();
            }
        };
    }
}
