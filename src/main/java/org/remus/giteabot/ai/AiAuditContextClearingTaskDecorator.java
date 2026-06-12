package org.remus.giteabot.ai;

import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Ensures the {@link AiAuditContext} thread-local never leaks between async
 * tasks: pooled executor threads are reused, so the session id set by one
 * webhook handler must not bleed into the next task running on that thread.
 *
 * <p>Picked up automatically by Spring Boot's task execution
 * auto-configuration as the {@link TaskDecorator} of the application task
 * executor that runs all {@code @Async} webhook handlers.</p>
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
