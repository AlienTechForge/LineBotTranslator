package com.linetranslate.bot.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeLogTest {

    @Test
    void pseudonymizesStableUserIdentifiers() {
        String rawUserId = "U0123456789abcdef";

        String first = SafeLog.user(rawUserId);
        String second = SafeLog.user(rawUserId);

        assertThat(first)
                .isEqualTo(second)
                .startsWith("usr_")
                .doesNotContain(rawUserId);
    }

    @Test
    void summarizesUserContentWithoutKeepingTheContent() {
        String secretContent = "seeded-private-message";

        assertThat(SafeLog.content(secretContent))
                .isEqualTo("chars=22")
                .doesNotContain(secretContent);
    }

    @Test
    void stripsCredentialsAndQueryParametersFromMongoUri() {
        String uri = "mongodb://seeded-user:seeded-password@mongo.example:27017/app"
                + "?authSource=admin&tlsCertificateKeyFile=secret.pem";

        assertThat(SafeLog.endpoint(uri))
                .isEqualTo("mongodb://mongo.example:27017/app")
                .doesNotContain("seeded-user", "seeded-password", "authSource", "secret.pem");
    }

    @Test
    void reportsOnlyExceptionTypes() {
        RuntimeException failure = new RuntimeException(
                "seeded-private-message",
                new IllegalStateException("seeded-password"));

        assertThat(SafeLog.failure(failure))
                .isEqualTo("RuntimeException<-IllegalStateException")
                .doesNotContain("seeded-private-message", "seeded-password");
    }

    @Test
    void exposesOnlySafeScalarStatusMetadata() {
        assertThat(SafeLog.httpStatus(429)).isEqualTo(429);
        assertThat(SafeLog.present("seeded-private-message")).isTrue();
        assertThat(SafeLog.present(" ")).isFalse();
        assertThat(SafeLog.metadata("gemini\nforged"))
                .isEqualTo("gemini_forged");
    }
}
