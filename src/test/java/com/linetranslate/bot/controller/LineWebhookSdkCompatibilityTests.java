package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.FlexMessage;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.service.translation.TranslationService;
import com.linetranslate.bot.service.webhook.WebhookClaim;
import com.linetranslate.bot.service.webhook.WebhookEventReceiptStore;

@SpringBootTest(properties = {
        "admin.users=U0123456789abcdef",
        "app.webhook.ingestion.core-threads=1",
        "app.webhook.ingestion.max-threads=1",
        "app.webhook.ingestion.queue-capacity=1",
        "app.webhook.ingestion.reply-retry-backoff=PT0S"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LineWebhookSdkCompatibilityTests {

    private static final String CHANNEL_SECRET = "test-channel-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessagingApiClient messagingApiClient;

    @MockitoBean
    private MessagingApiBlobClient messagingApiBlobClient;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @MockitoBean
    private ImageTranslationService imageTranslationService;

    @MockitoBean
    private TranslationService translationService;

    @MockitoBean
    private WebhookEventReceiptStore receiptStore;

    @BeforeEach
    void allowUniqueClaimsByDefault() {
        AtomicInteger claims = new AtomicInteger();
        when(receiptStore.claim(any())).thenAnswer(invocation -> {
            var envelope = invocation.getArgument(0,
                    com.linetranslate.bot.service.webhook.WebhookEventEnvelope.class);
            return WebhookClaim.claimed(
                    envelope.eventId(),
                    "claim-" + claims.incrementAndGet());
        });
    }

    @Test
    void signedTextWebhookIsParsedRoutedAndRepliedWithSdkTenModels() throws Exception {
        String payload = """
                {
                  "destination": "U0123456789abcdef",
                  "events": [
                    {
                      "replyToken": "reply-token",
                      "type": "message",
                      "timestamp": 1462629479859,
                      "mode": "active",
                      "webhookEventId": "event-help",
                      "deliveryContext": { "isRedelivery": false },
                      "source": {
                        "type": "user",
                        "userId": "U0123456789abcdef"
                      },
                      "message": {
                        "id": "325708",
                        "type": "text",
                        "text": "/help"
                      }
                    }
                  ]
                }
                """;
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(payload))
                        .content(payload))
                .andExpect(status().isOk());

        ArgumentCaptor<ReplyMessageRequest> captor = ArgumentCaptor.forClass(ReplyMessageRequest.class);
        verify(messagingApiClient, timeout(1_000)).replyMessage(captor.capture());

        ReplyMessageRequest request = captor.getValue();
        assertThat(request.replyToken()).isEqualTo("reply-token");
        assertThat(request.messages()).singleElement().isInstanceOfSatisfying(
                TextMessage.class,
                message -> assertThat(message.text()).contains("LINE 翻譯機器人幫助"));
    }

    @Test
    void signedImageWebhookUsesSdkTenImageContentAndReplies() throws Exception {
        String payload = """
                {
                  "destination": "U0123456789abcdef",
                  "events": [
                    {
                      "replyToken": "image-reply-token",
                      "type": "message",
                      "timestamp": 1462629479859,
                      "mode": "active",
                      "webhookEventId": "event-image",
                      "deliveryContext": { "isRedelivery": false },
                      "source": {
                        "type": "user",
                        "userId": "U0123456789abcdef"
                      },
                      "message": {
                        "id": "image-message-id",
                        "type": "image",
                        "contentProvider": { "type": "line" }
                      }
                    }
                  ]
                }
                """;
        when(imageTranslationService.processImageTranslation(
                "U0123456789abcdef", "image-message-id"))
                .thenReturn("圖片翻譯完成");
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verify(imageTranslationService).processImageTranslation(
                "U0123456789abcdef", "image-message-id");
        ArgumentCaptor<ReplyMessageRequest> captor = ArgumentCaptor.forClass(ReplyMessageRequest.class);
        verify(messagingApiClient, timeout(1_000)).replyMessage(captor.capture());
        assertThat(captor.getValue().messages()).singleElement().isInstanceOfSatisfying(
                TextMessage.class,
                message -> assertThat(message.text()).isEqualTo("圖片翻譯完成"));
    }

    @Test
    void signedAdminWebhookRepliesWithFlexDashboard() throws Exception {
        String payload = textPayload("admin-reply-token", "U0123456789abcdef", "/admin");
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(payload))
                        .content(payload))
                .andExpect(status().isOk());

        ArgumentCaptor<ReplyMessageRequest> captor = ArgumentCaptor.forClass(ReplyMessageRequest.class);
        verify(messagingApiClient, timeout(1_000)).replyMessage(captor.capture());
        assertThat(captor.getValue().messages()).singleElement().isInstanceOfSatisfying(
                FlexMessage.class,
                message -> assertThat(message.altText()).contains("管理員控制台"));
    }

    @Test
    void unauthorizedAdminWebhookRepliesWithAccessDeniedCard() throws Exception {
        String payload = textPayload("denied-reply-token", "U-not-admin", "/admin stats");
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(payload))
                        .content(payload))
                .andExpect(status().isOk());

        ArgumentCaptor<ReplyMessageRequest> captor = ArgumentCaptor.forClass(ReplyMessageRequest.class);
        verify(messagingApiClient, timeout(1_000)).replyMessage(captor.capture());
        assertThat(captor.getValue().messages()).singleElement().isInstanceOfSatisfying(
                FlexMessage.class,
                message -> assertThat(message.altText()).contains("沒有管理員權限"));
    }

    @Test
    void invalidSignatureIsRejectedBeforeClaimOrDispatch() throws Exception {
        String payload = textPayload("invalid-reply", "U-user", "hello");

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", "invalid-signature")
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(receiptStore, never()).claim(any());
        verify(translationService, never()).processTranslationRequest(anyString(), anyString());
    }

    @Test
    void redeliveryWithSameWebhookEventIdDoesNotTranslateOrReplyTwice() throws Exception {
        String original = textPayload(
                "duplicate-reply", "U-user", "hello", "event-duplicate", false);
        String redelivery = textPayload(
                "duplicate-reply", "U-user", "hello", "event-duplicate", true);
        doReturn(WebhookClaim.claimed("event-duplicate", "claim-duplicate"))
                .doReturn(WebhookClaim.duplicate("event-duplicate"))
                .when(receiptStore).claim(any());
        when(translationService.processTranslationRequest("U-user", "hello"))
                .thenReturn("translated");
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(original))
                        .content(original))
                .andExpect(status().isOk());
        mockMvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", signature(redelivery))
                        .content(redelivery))
                .andExpect(status().isOk());

        verify(translationService, timeout(2_000)).processTranslationRequest("U-user", "hello");
        verify(messagingApiClient, timeout(2_000)).replyMessage(any());
        verify(translationService, times(1)).processTranslationRequest("U-user", "hello");
        verify(messagingApiClient, times(1)).replyMessage(any());
    }

    @Test
    void longTranslationDoesNotDelayWebhookAcknowledgement() throws Exception {
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        when(translationService.processTranslationRequest("U-slow", "slow"))
                .thenAnswer(invocation -> {
                    processingStarted.countDown();
                    releaseProcessing.await(5, TimeUnit.SECONDS);
                    return "done";
                });
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        String payload = textPayload("slow-reply", "U-slow", "slow", "event-slow", false);

        long startedAt = System.nanoTime();
        try {
            mockMvc.perform(post("/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Line-Signature", signature(payload))
                            .content(payload))
                    .andExpect(status().isOk());
            Duration acknowledgement = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(acknowledgement).isLessThan(Duration.ofSeconds(1));
            assertThat(processingStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProcessing.countDown();
        }
        verify(messagingApiClient, timeout(2_000)).replyMessage(any());
    }

    @Test
    void fullBoundedQueueReturnsServiceUnavailableAndReleasesRejectedClaim() throws Exception {
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        when(translationService.processTranslationRequest(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    processingStarted.countDown();
                    releaseProcessing.await(5, TimeUnit.SECONDS);
                    return "done";
                });
        when(messagingApiClient.replyMessage(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        String first = textPayload("queue-reply-1", "U-queue", "one", "event-queue-1", false);
        String second = textPayload("queue-reply-2", "U-queue", "two", "event-queue-2", false);
        String rejected = textPayload("queue-reply-3", "U-queue", "three", "event-queue-3", false);

        try {
            mockMvc.perform(post("/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Line-Signature", signature(first))
                            .content(first))
                    .andExpect(status().isOk());
            assertThat(processingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(post("/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Line-Signature", signature(second))
                            .content(second))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Line-Signature", signature(rejected))
                            .content(rejected))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            releaseProcessing.countDown();
        }

        verify(receiptStore).release(argThat(claim -> claim.eventId().equals("event-queue-3")));
        verify(messagingApiClient, timeout(3_000).times(2)).replyMessage(any());
    }

    private static String textPayload(String replyToken, String userId, String text) {
        return textPayload(replyToken, userId, text, "event-" + replyToken, false);
    }

    private static String textPayload(
            String replyToken,
            String userId,
            String text,
            String webhookEventId,
            boolean redelivery) {
        return """
                {
                  "destination": "U0123456789abcdef",
                  "events": [
                    {
                      "replyToken": "%s",
                      "type": "message",
                      "timestamp": 1462629479859,
                      "mode": "active",
                      "webhookEventId": "%s",
                      "deliveryContext": { "isRedelivery": %s },
                      "source": { "type": "user", "userId": "%s" },
                      "message": { "id": "325709", "type": "text", "text": "%s" }
                    }
                  ]
                }
                """.formatted(replyToken, webhookEventId, redelivery, userId, text);
    }

    private static String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
