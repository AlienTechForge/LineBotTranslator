package com.linetranslate.bot.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import io.grpc.Metadata;

class ProductionClasspathCompatibilityTests {

    @Test
    void grpcMetadataCanValidateHeadersWithResolvedGuavaVersion() {
        assertThatCode(() -> Metadata.Key.of(
                "x-linebot-test",
                Metadata.ASCII_STRING_MARSHALLER))
                .doesNotThrowAnyException();
    }
}
