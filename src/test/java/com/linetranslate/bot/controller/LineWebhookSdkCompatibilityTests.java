package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.storage.MinioStorageService;

@SpringBootTest
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

    private static String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
