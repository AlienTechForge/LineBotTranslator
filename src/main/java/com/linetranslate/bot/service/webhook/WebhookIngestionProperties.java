package com.linetranslate.bot.service.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Component
@Validated
@ConfigurationProperties(prefix = "app.webhook.ingestion")
public class WebhookIngestionProperties {

    @Min(1)
    private int coreThreads = 2;

    @Min(1)
    private int maxThreads = 4;

    @Min(1)
    private int queueCapacity = 100;

    @NotNull
    private Duration receiptTtl = Duration.ofDays(7);

    @NotNull
    private Duration processingLease = Duration.ofMinutes(5);

    @Min(1)
    private int replyMaxAttempts = 3;

    @NotNull
    private Duration replyRetryBackoff = Duration.ofSeconds(1);

    public int getCoreThreads() {
        return coreThreads;
    }

    public void setCoreThreads(int coreThreads) {
        this.coreThreads = coreThreads;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getReceiptTtl() {
        return receiptTtl;
    }

    public void setReceiptTtl(Duration receiptTtl) {
        this.receiptTtl = receiptTtl;
    }

    public Duration getProcessingLease() {
        return processingLease;
    }

    public void setProcessingLease(Duration processingLease) {
        this.processingLease = processingLease;
    }

    public int getReplyMaxAttempts() {
        return replyMaxAttempts;
    }

    public void setReplyMaxAttempts(int replyMaxAttempts) {
        this.replyMaxAttempts = replyMaxAttempts;
    }

    public Duration getReplyRetryBackoff() {
        return replyRetryBackoff;
    }

    public void setReplyRetryBackoff(Duration replyRetryBackoff) {
        this.replyRetryBackoff = replyRetryBackoff;
    }
}
