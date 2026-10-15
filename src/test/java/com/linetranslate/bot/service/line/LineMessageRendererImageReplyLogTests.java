package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.bot.messaging.model.ImageMessage;
import com.linetranslate.bot.service.ai.AiModelCatalog;
import com.linetranslate.bot.service.imageproxy.DwzShortLinkService;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinkService;
import com.linetranslate.bot.service.imageproxy.ImageProxyLinks;
import com.linetranslate.bot.service.ocr.ImageTranslationReply;
import com.linetranslate.bot.service.settings.RuntimeSettings;
import com.linetranslate.bot.service.settings.RuntimeSettingsSource;
import com.linetranslate.bot.service.translation.TranslationResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The image reply decision is the last thing this service can say about an image that LINE then
 * fails to show: which branch was taken, and where LINE was told to fetch it from. The log must
 * say that without the signed URL, whose query string is a credential.
 */
class LineMessageRendererImageReplyLogTests {

    private static final String SIGNATURE = "a1b2c3d4".repeat(8);
    private static final String SIGNED_IMAGE = "https://s3.azndev.com/line-bot/translated-images/1/secret-name.png"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=test%2F20260812%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
            + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + SIGNATURE;
    private static final String SIGNED_PREVIEW = "https://s3.azndev.com/line-bot/translated-images/1/secret-name-preview.jpg"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=" + SIGNATURE;
    private static final String PROXY_TOKEN = "0123456789abcdefghij-_";

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(LineMessageRenderer.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void signedImageBranchNamesTheHostAndThatNoPreviewWasStored() {
        List<?> messages = renderer(true, false, null, null).imageResultMessages(reply(SIGNED_IMAGE, null));

        assertThat(messages).hasSize(2);
        assertThat(onlyLine())
                .contains("branch=SIGNED_IMAGE", "reason=none", "imageHost=s3.azndev.com",
                        "previewUrlPresent=false", "shortened=false", "textReply=true", "messages=2");
        assertSafe(onlyLine());
    }

    @Test
    void storedPreviewIsReportedAsPresent() {
        renderer(true, false, null, null).imageResultMessages(reply(SIGNED_IMAGE, SIGNED_PREVIEW));

        assertThat(onlyLine()).contains("branch=SIGNED_IMAGE", "previewUrlPresent=true");
        assertSafe(onlyLine());
    }

    @Test
    void shortLinkReportsTheShortHostAndThatItWasShortened() {
        DwzShortLinkService shortLinks = mock(DwzShortLinkService.class);
        when(shortLinks.shortenSignedImage(URI.create(SIGNED_IMAGE)))
                .thenReturn(Optional.of(URI.create("https://s.azndev.com/abc")));

        renderer(true, true, null, shortLinks).imageResultMessages(reply(SIGNED_IMAGE, SIGNED_PREVIEW));

        assertThat(onlyLine())
                .contains("branch=SIGNED_IMAGE", "imageHost=s.azndev.com", "shortened=true");
        assertSafe(onlyLine());
    }

    @Test
    void proxyBranchNamesTheProxyHostNotTheToken() {
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        when(proxy.register(SIGNED_IMAGE)).thenReturn(Optional.of(new ImageProxyLinks(
                URI.create("https://translate.azndev.com/i/" + PROXY_TOKEN),
                URI.create("https://translate.azndev.com/i/" + PROXY_TOKEN + "/preview"))));

        renderer(true, false, proxy, null).imageResultMessages(reply(SIGNED_IMAGE, SIGNED_PREVIEW));

        assertThat(onlyLine())
                .contains("branch=PROXY_LINKS", "imageHost=translate.azndev.com",
                        "previewUrlPresent=true", "shortened=false", "textReply=true");
        assertThat(onlyLine()).doesNotContain(PROXY_TOKEN);
        assertSafe(onlyLine());
    }

    @Test
    void proxyThatDeclinesTheUrlFallsThroughToTheSignedImage() {
        ImageProxyLinkService proxy = mock(ImageProxyLinkService.class);
        when(proxy.register(SIGNED_IMAGE)).thenReturn(Optional.empty());

        renderer(true, false, proxy, null).imageResultMessages(reply(SIGNED_IMAGE, null));

        assertThat(onlyLine()).contains("branch=SIGNED_IMAGE", "imageHost=s3.azndev.com");
    }

    @Test
    void disabledTextReplyIsReported() {
        List<?> messages = renderer(false, false, null, null).imageResultMessages(reply(SIGNED_IMAGE, null));

        assertThat(messages).singleElement().isInstanceOf(ImageMessage.class);
        assertThat(onlyLine()).contains("branch=SIGNED_IMAGE", "textReply=false", "messages=1");
    }

    @Test
    void replyWithoutAGeneratedImageIsLoggedAsTheTextFallback() {
        renderer(true, false, null, null).imageResultMessages(
                ImageTranslationReply.text(TranslationResponse.plain("hello")));

        assertThat(onlyLine())
                .contains("branch=TEXT_FALLBACK", "reason=no_rendered_image", "imageHost=none",
                        "previewUrlPresent=false", "textReply=false", "messages=1");
    }

    @Test
    void unsafeImageUrlFallsBackToTextWithoutLoggingTheUrl() {
        String unsafe = "http://user:hunter2@s3.azndev.com/translated-images/1/secret-name.png?token=hunter2";

        renderer(true, false, null, null).imageResultMessages(reply(unsafe, null));

        assertThat(onlyLine())
                .contains("branch=TEXT_FALLBACK", "reason=unsafe_image_url", "imageHost=none");
        assertThat(onlyLine()).doesNotContain("hunter2", "s3.azndev.com", "secret-name");
    }

    @Test
    void missingReplyIsLoggedAsTheTextFallback() {
        renderer(true, false, null, null).imageResultMessages(null);

        assertThat(onlyLine()).contains("branch=TEXT_FALLBACK", "reason=no_reply");
    }

    @Test
    void renderingTheMessageAloneDoesNotLog() {
        renderer(true, false, null, null).imageResult(reply(SIGNED_IMAGE, null));

        assertThat(logs.list).isEmpty();
    }

    private String onlyLine() {
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).startsWith("Image reply composed: ");
        });
        return logs.list.get(0).getFormattedMessage();
    }

    private static void assertSafe(String line) {
        assertThat(line).doesNotContain("X-Amz", SIGNATURE, "secret-name", "translated-images", "https://", "?");
    }

    private static ImageTranslationReply reply(String image, String preview) {
        TranslationResponse response = new TranslationResponse(
                "【翻譯圖片】\n" + image + "\n\nhello\n你好", "你好", "507f1f77bcf86cd799439011", "en", "zh-TW");
        return new ImageTranslationReply(response, Optional.of(image), Optional.ofNullable(preview));
    }

    private static LineMessageRenderer renderer(
            boolean imageTextReplyEnabled, boolean shortUrlEnabled,
            ImageProxyLinkService proxy, DwzShortLinkService shortLinks) {
        RuntimeSettingsSource settings = () -> new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, shortUrlEnabled, imageTextReplyEnabled,
                4, 1, null, null, RuntimeSettings.Source.PERSISTED);
        return new LineMessageRenderer(mock(AiModelCatalog.class), settings, proxy, shortLinks);
    }
}
