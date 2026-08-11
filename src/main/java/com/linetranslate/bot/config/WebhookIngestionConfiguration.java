package com.linetranslate.bot.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.linetranslate.bot.service.webhook.WebhookIngestionProperties;

@Configuration
public class WebhookIngestionConfiguration {

    @Bean(name = "webhookIngestionExecutor")
    ThreadPoolTaskExecutor webhookIngestionExecutor(WebhookIngestionProperties properties) {
        if (properties.getCoreThreads() > properties.getMaxThreads()) {
            throw new IllegalArgumentException(
                    "Webhook core thread count must not exceed max thread count");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreThreads());
        executor.setMaxPoolSize(properties.getMaxThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("webhook-ingestion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
