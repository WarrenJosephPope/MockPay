package dev.mockpay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The thread pool that outbound email runs on.
 *
 * <p>Separate from the scheduler that dispatches webhooks, and deliberately small. Mail is a
 * best-effort notification; webhooks are a contractual delivery guarantee. Sharing a pool would let
 * a hanging SMTP connection delay webhook dispatch, which is the more important of the two.
 */
@Configuration
@EnableAsync
public class MailConfig {

    @Bean("mailExecutor")
    Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");

        // Drop the oldest queued message rather than blocking the caller or throwing. If 200
        // emails are already waiting, the mail server is down and the queue is stale — the right
        // failure is a lost notification, not a stalled request thread.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());

        // Do not hold shutdown open for queued mail. A deploy should not wait on SMTP.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
