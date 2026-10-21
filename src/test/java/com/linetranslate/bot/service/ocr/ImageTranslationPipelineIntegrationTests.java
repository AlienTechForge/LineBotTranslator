package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linetranslate.bot.model.TranslationRecord;
import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.repository.TranslationRecordRepository;
import com.linetranslate.bot.service.line.LineInteractionModule;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.storage.ImageStorageResult;
import com.linetranslate.bot.service.storage.MinioStorageService;
import com.linetranslate.bot.testing.ImageTranslatorFixtures;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/**
 * An image message from LINE through the whole bot: the real Spring context and MongoDB, with a
 * {@link MockWebServer} standing in for the image-translator service and the LINE content API and the
 * object storage stubbed. It follows the picture from the LINE message to the messages of the reply and
 * to the record in MongoDB.
 */
@SpringBootTest(properties = {"app.ocr.enabled=true", "openrouter.api.key=test-openrouter-key"})
@ActiveProfiles("test")
class ImageTranslationPipelineIntegrationTests {

    private static final String USER = "U0123456789abcdef0123456789abcdef";
    private static final String ORIGINAL = "https://storage.example/original.png?signature=orig";
    private static final String TRANSLATED = "https://storage.example/translated.png?signature=translated";
    private static final Pattern REQUEST_ID = Pattern.compile("name=\"request_id\"\r\n\r\n([^\r]*)\r\n");
    private static final byte[] TRANSLATED_PNG = ImageTranslatorFixtures.png(48, 32);

    private static final MockWebServer SERVICE = new MockWebServer();
    private static final Queue<Call> CALLS = new ConcurrentLinkedQueue<>();
    private static volatile String scenario = "clean";

    @MockitoBean
    private MessagingApiBlobClient blobClient;

    @MockitoBean
    private MinioStorageService minio;

    @Autowired
    private LineInteractionModule interaction;

    @Autowired
    private UserPreferencesModule preferences;

    @Autowired
    private TranslationRecordRepository records;

    @Autowired
    private MongoTemplate mongo;

    @DynamicPropertySource
    static void theServiceIsTheMockServer(DynamicPropertyRegistry registry) throws IOException {
        SERVICE.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readString(StandardCharsets.ISO_8859_1);
                CALLS.add(new Call(request.getMethod(), request.getPath(), request.getHeader("X-Service-Token"), body));
                String path = request.getPath();
                if ("/healthz".equals(path)) {
                    return json(200, ImageTranslatorFixtures.healthJson());
                }
                if ("POST".equals(request.getMethod()) && "/v1/translations".equals(path)) {
                    Matcher id = REQUEST_ID.matcher(body);
                    return answer(id.find() ? id.group(1) : "unknown");
                }
                if (path != null && path.startsWith("/v1/translations/") && path.endsWith("/image.png")) {
                    return new MockResponse().setResponseCode(200).addHeader("Content-Type", "image/png")
                            .setBody(new Buffer().write(TRANSLATED_PNG));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        SERVICE.start();
        registry.add("app.image-translator.url", () -> SERVICE.url("/").toString());
        registry.add("app.image-translator.token", () -> ImageTranslatorFixtures.TOKEN);
    }

    @AfterAll
    static void stopTheServer() throws IOException {
        SERVICE.shutdown();
    }

    @BeforeEach
    void cleanState() {
        for (String collection : List.of("translation_records", "user_profiles", "runtime_settings")) {
            if (mongo.collectionExists(collection)) {
                mongo.dropCollection(collection);
            }
        }
        CALLS.clear();
        scenario = "clean";
        when(minio.uploadImage(any(byte[].class), anyString())).thenReturn(ImageStorageResult.stored(ORIGINAL));
        when(minio.uploadTranslatedImage(any(byte[].class), anyString()))
                .thenReturn(ImageStorageResult.stored(TRANSLATED));
    }

    @Test
    void anImageMessageIsTranslatedByTheServiceStoredAndAnsweredWithThePictureOnly() {
        preferences.updateTargetLanguage(USER, "ja");
        byte[] upload = ImageTranslatorFixtures.png(64, 48);
        lineSends("line-message-1", upload);

        List<Message> messages = interaction.executeImage(USER, "line-message-1");

        // the reply is the picture alone
        assertThat(messages).singleElement().isInstanceOfSatisfying(ImageMessage.class,
                image -> assertThat(image.originalContentUrl().toString()).isEqualTo(TRANSLATED));
        // the original went to storage, the translated PNG too
        verify(minio).uploadImage(any(byte[].class), eq("image/png"));
        verify(minio).uploadTranslatedImage(TRANSLATED_PNG, "image/png");
        // the service saw the picture, the user's language and the LINE message id, and was asked for its picture
        List<Call> calls = serviceCalls();
        assertThat(calls).extracting(Call::method).containsExactly("POST", "GET");
        Call post = calls.get(0);
        assertThat(post.path()).isEqualTo("/v1/translations");
        assertThat(post.token()).isEqualTo(ImageTranslatorFixtures.TOKEN);
        assertThat(post.body())
                .contains("name=\"image\"; filename=\"image.png\"")
                .contains("name=\"target_lang\"\r\n\r\nja\r\n")
                .contains("name=\"request_id\"\r\n\r\nline-message-1\r\n")
                .contains(new String(upload, StandardCharsets.ISO_8859_1));
        assertThat(calls.get(1).path()).isEqualTo("/v1/translations/line-message-1/image.png");
        assertThat(calls.get(1).token()).isEqualTo(ImageTranslatorFixtures.TOKEN);
        // the record in MongoDB carries the texts, the storage of the original and the service's numbers
        List<TranslationRecord> saved = records.findAll();
        assertThat(saved).singleElement().satisfies(record -> {
            assertThat(record.getUserId()).isEqualTo(USER);
            assertThat(record.isImageTranslation()).isTrue();
            assertThat(record.getSourceText()).isEqualTo("TONKATSU SET MENU NT$120");
            assertThat(record.getTranslatedText()).isEqualTo("炸豬排套餐\nNT$120");
            assertThat(record.getSourceLanguage()).isEqualTo("en");
            assertThat(record.getTargetLanguage()).isEqualTo("ja");
            assertThat(record.getAiProvider()).isEqualTo("image-translator");
            assertThat(record.getModelName()).isEqualTo("A");
            assertThat(record.getImageUrl()).isEqualTo(ORIGINAL);
            assertThat(record.getImageStored()).isTrue();
            assertThat(record.getImageMode()).isEqualTo("A");
            assertThat(record.getImageTier()).isEqualTo("clean");
            assertThat(record.getImageVerdict()).isEqualTo("pass");
            assertThat(record.getImageUsability()).isEqualTo(0.82);
            assertThat(record.getImageCostUsd()).isEqualTo(0.0523);
        });
        // ... and the user's counters moved
        UserProfile profile = preferences.profile(USER);
        assertThat(profile.getImageTranslations()).isEqualTo(1);
    }

    @Test
    void aWarnedPictureIsFollowedByTheWarningLine() {
        scenario = "warned";
        lineSends("line-message-2", ImageTranslatorFixtures.png(64, 48));

        List<Message> messages = interaction.executeImage(USER, "line-message-2");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(ImageMessage.class);
        assertThat(((TextMessage) messages.get(1)).text()).isEqualTo("⚠️ 圖片翻譯可能有遺漏或錯字，數字請以原圖為準");
        assertThat(records.findAll()).singleElement()
                .satisfies(record -> assertThat(record.getImageTier()).isEqualTo("warned"));
    }

    @Test
    void textOnlyIsAnsweredWithTheTranslatedTextAndFetchesNoPicture() {
        scenario = "text_only";
        lineSends("line-message-3", ImageTranslatorFixtures.png(64, 48));

        List<Message> messages = interaction.executeImage(USER, "line-message-3");

        assertThat(messages).singleElement().isInstanceOfSatisfying(TextMessage.class, text ->
                assertThat(text.text()).isEqualTo("圖片無法完整翻譯，以下是文字翻譯：\n你好"));
        assertThat(serviceCalls()).extracting(Call::method).containsExactly("POST");
        assertThat(records.findAll()).singleElement().satisfies(record -> {
            assertThat(record.getTranslatedText()).isEqualTo("你好");
            assertThat(record.getImageTier()).isNull();
        });
    }

    @Test
    void aServiceThatAnswersWithAnHttpErrorIsReportedAsUnavailableAndNothingIsSaved() {
        scenario = "http_503";
        lineSends("line-message-4", ImageTranslatorFixtures.png(64, 48));

        List<Message> messages = interaction.executeImage(USER, "line-message-4");

        assertThat(messages).singleElement().isInstanceOfSatisfying(TextMessage.class, text ->
                assertThat(text.text()).isEqualTo("圖片翻譯服務暫時無法使用，請稍後再試。"));
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void aBudgetThatIsUsedUpHasItsOwnReply() {
        scenario = "budget";
        lineSends("line-message-5", ImageTranslatorFixtures.png(64, 48));

        assertThat(interaction.executeImage(USER, "line-message-5")).singleElement()
                .isInstanceOfSatisfying(TextMessage.class, text ->
                        assertThat(text.text()).isEqualTo("本月圖片翻譯額度已用完，請下個月再試。"));
    }

    // ------------------------------------------------------------------ the stand-in service

    private static MockResponse answer(String requestId) {
        return switch (scenario) {
            case "warned" -> json(200, ImageTranslatorFixtures.imageJson(requestId, "warned", "a_better", "B", 100));
            case "text_only" -> json(200, ImageTranslatorFixtures.textOnlyJson(requestId, "你好"));
            case "http_503" -> json(503, ImageTranslatorFixtures.httpErrorJson(
                    "service_token_missing", "SERVICE_TOKEN is not set: the service is not configured"));
            case "budget" -> json(200, ImageTranslatorFixtures.errorJson(requestId, "anthropic_budget"));
            default -> json(200, ImageTranslatorFixtures.imageJson(requestId, "clean", "a_pass", "A", 100));
        };
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
    }

    private static List<Call> serviceCalls() {
        return CALLS.stream().filter(call -> !"/healthz".equals(call.path())).toList();
    }

    @SuppressWarnings("unchecked")
    private void lineSends(String messageId, byte[] bytes) {
        Result<BlobContent> result = mock(Result.class);
        BlobContent content = mock(BlobContent.class);
        when(blobClient.getMessageContent(messageId)).thenReturn(CompletableFuture.completedFuture(result));
        when(result.body()).thenReturn(content);
        when(content.byteStream()).thenReturn(new java.io.ByteArrayInputStream(bytes));
        when(content.mimeType()).thenReturn("image/png");
    }

    private record Call(String method, String path, String token, String body) {
    }
}
