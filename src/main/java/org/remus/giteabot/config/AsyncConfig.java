package org.remus.giteabot.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Wires any {@link TaskDecorator} bean into the application's
 * {@link ThreadPoolTaskExecutor} instances.
 *
 * <p>Spring Boot does <strong>not</strong> auto-discover {@link TaskDecorator}
 * beans for its async executor. This post-processor bridges that gap so that
 * decorators (e.g. for clearing thread-locals) are applied to every
 * {@code @Async} task without the {@code config} package needing to know
 * about domain-specific decorator classes.</p>
 */
@Component
public class AsyncConfig implements BeanPostProcessor {

    private final TaskDecorator taskDecorator;

    public AsyncConfig(TaskDecorator taskDecorator) {
        this.taskDecorator = taskDecorator;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ThreadPoolTaskExecutor executor) {
            executor.setTaskDecorator(taskDecorator);
        }
        return bean;
    }
}
