package com.linetranslate.bot.service.imageproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ImageProxyLinkServiceTests {

    private static final String SIGNED_IMAGE = "https://s3.azndev.com/line-bot/translated-images/123/result.png"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=access%2Fscope"
            + "&X-Amz-Date=20260812T071753Z&X-Amz-Expires=3600"
            + "&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "a".repeat(64);

    @Test
    void registersOpaqueCleanUrlsAndResolvesOnlyTheIssuedToken() {
        ImageProxyLinkService service = new ImageProxyLinkService(
                "https://translate.azndev.com",
                "https://s3.azndev.com",
                "line-bot",
                Duration.ofMinutes(55),
                100,
                () -> "0123456789abcdefghij-_"
        );

        ImageProxyLinks links = service.register(SIGNED_IMAGE).orElseThrow();

        assertThat(links.original().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_")
                .doesNotContain("X-Amz", "s3.azndev.com");
        assertThat(links.preview().toString())
                .isEqualTo("https://translate.azndev.com/i/0123456789abcdefghij-_/preview");
        assertThat(service.resolve("0123456789abcdefghij-_"))
                .contains(java.net.URI.create(SIGNED_IMAGE));
        assertThat(service.resolve("0123456789abcdefghij-x")).isEmpty();
    }

    @Test
    void rejectsUrlsThatCouldTurnTheProxyIntoAnSsrfPrimitive() {
        ImageProxyLinkService service = new ImageProxyLinkService(
                "https://translate.azndev.com",
                "https://s3.azndev.com",
                "line-bot",
                Duration.ofMinutes(55),
                100,
                () -> "0123456789abcdefghij-_"
        );

        assertThat(service.register("https://evil.example/line-bot/translated-images/x.png?X-Amz-Signature=x"))
                .isEmpty();
        assertThat(service.register("https://s3.azndev.com/line-bot/uploads/x.png?X-Amz-Signature=x"))
                .isEmpty();
        assertThat(service.register("https://user@s3.azndev.com/line-bot/translated-images/x.png?X-Amz-Signature=x"))
                .isEmpty();
        assertThat(service.register("not-a-url")).isEmpty();
    }
}
