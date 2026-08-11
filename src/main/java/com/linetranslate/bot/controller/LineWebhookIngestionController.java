package com.linetranslate.bot.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.bot.parser.WebhookParseException;
import com.linecorp.bot.parser.WebhookParser;
import com.linecorp.bot.webhook.model.Event;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.webhook.WebhookEventEnvelope;
import com.linetranslate.bot.service.webhook.WebhookIngestionModule;
import com.linetranslate.bot.service.webhook.WebhookIngestionResult;

import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class LineWebhookIngestionController {

    private final WebhookParser webhookParser;
    private final WebhookIngestionModule ingestionModule;

    public LineWebhookIngestionController(
            WebhookParser webhookParser,
            WebhookIngestionModule ingestionModule) {
        this.webhookParser = webhookParser;
        this.ingestionModule = ingestionModule;
    }

    @PostMapping("${line.bot.handler.path:/callback}")
    public ResponseEntity<Void> callback(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        try {
            List<Event> events = webhookParser.handle(signature, body).events();
            events.forEach(WebhookEventEnvelope::from);

            boolean rejected = events.stream()
                    .map(ingestionModule::ingest)
                    .anyMatch(WebhookIngestionResult.REJECTED::equals);
            return ResponseEntity.status(
                    rejected ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).build();
        } catch (WebhookParseException | IOException | IllegalArgumentException invalidRequest) {
            log.warn("LINE webhook rejected: failure={}", SafeLog.failure(invalidRequest));
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException unavailable) {
            log.error("LINE webhook ingestion unavailable: failure={}", SafeLog.failure(unavailable));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
