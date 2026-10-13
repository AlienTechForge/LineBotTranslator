package com.linetranslate.bot.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

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
    void redactedFailureKeepsTypesAndStackFramesButDropsEveryMessage() {
        RuntimeException failure = new RuntimeException(
                "seeded-private-message https://storage.example/x?token=seeded-password",
                new IllegalStateException("seeded-cause-message"));
        failure.addSuppressed(new IllegalArgumentException("seeded-suppressed-message"));

        Throwable redacted = SafeLog.redacted(failure);

        assertThat(redacted.getMessage()).isEqualTo("java.lang.RuntimeException");
        assertThat(redacted.getCause().getMessage()).isEqualTo("java.lang.IllegalStateException");
        assertThat(redacted.getSuppressed()).isEmpty();
        assertThat(redacted.getStackTrace()).isEqualTo(failure.getStackTrace());
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(failure.getCause().getStackTrace());
        StringWriter printed = new StringWriter();
        redacted.printStackTrace(new PrintWriter(printed));
        assertThat(printed.toString())
                .contains("java.lang.RuntimeException", "java.lang.IllegalStateException")
                .contains(SafeLogTest.class.getName())
                .doesNotContain("seeded");
    }

    @Test
    void redactedFailureBoundsTheCauseChainLikeTheTypeSummary() {
        Throwable failure = new RuntimeException("1");
        for (int depth = 2; depth <= 6; depth++) {
            failure = new RuntimeException(String.valueOf(depth), failure);
        }

        Throwable redacted = SafeLog.redacted(failure);

        int types = 0;
        for (Throwable current = redacted; current != null; current = current.getCause()) {
            types++;
        }
        assertThat(types).isEqualTo(SafeLog.failure(failure).split("<-").length).isEqualTo(4);
        assertThat(SafeLog.redacted(null)).isNull();
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
