package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

import com.linetranslate.bot.service.storage.MinioObjectBrowser;

import io.minio.MinioClient;

/** The whole feature is gated on one non-blank property, evaluated before any bean is created. */
class AdminImageGalleryRegistrationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AdminImageGalleryController.class,
                    AdminImageGalleryWebConfiguration.class,
                    MinioObjectBrowser.class)
            .withPropertyValues("minio.bucket-name=line-bot");

    @Test
    void nothingIsRegisteredWhenTheTokenIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertNothingRegistered(context);
        });
    }

    @Test
    void nothingIsRegisteredWhenTheTokenIsEmpty() {
        contextRunner.withPropertyValues("app.admin.web.token=").run(context -> {
            assertThat(context).hasNotFailed();
            assertNothingRegistered(context);
        });
    }

    @Test
    void nothingIsRegisteredWhenTheTokenIsOnlyWhitespace() {
        contextRunner.withPropertyValues("app.admin.web.token=   ").run(context -> {
            assertThat(context).hasNotFailed();
            assertNothingRegistered(context);
        });
    }

    @Test
    void everythingIsRegisteredWhenATokenIsConfigured() {
        contextRunner
                .withBean("minioClient", MinioClient.class, () -> MinioClient.builder()
                        .endpoint("http://127.0.0.1:9000")
                        .credentials("test-access", "test-secret")
                        .build())
                .withPropertyValues("app.admin.web.token=a-long-random-admin-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AdminImageGalleryController.class);
                    assertThat(context).hasSingleBean(AdminImageGalleryWebConfiguration.class);
                    assertThat(context).hasSingleBean(MinioObjectBrowser.class);
                });
    }

    @Test
    void applicationPropertiesMapTheEnvironmentVariableAndDefaultToBlank() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }

        assertThat(properties.getProperty("app.admin.web.token")).isEqualTo("${ADMIN_WEB_TOKEN:}");
    }

    @Test
    void deploymentAndDocumentationCarryTheOptionalToken() throws IOException {
        String workflow = Files.readString(Path.of(".github", "workflows", "ci-cd.yml"));
        String deployScript = Files.readString(Path.of("scripts", "deploy.sh"));

        // A secret, passed through unchanged: an unset secret becomes an empty value, which disables the page.
        assertThat(workflow).contains("ADMIN_WEB_TOKEN: ${{ secrets.ADMIN_WEB_TOKEN }}");
        assertThat(deployScript).containsPattern("(?m)^\\s+ADMIN_WEB_TOKEN$");
        for (String document : new String[] {".env.example", "README.md", "deployment-guide.md"}) {
            assertThat(Files.readString(Path.of(document))).as(document).contains("ADMIN_WEB_TOKEN");
        }
    }

    private static void assertNothingRegistered(ApplicationContext context) {
        assertThat(context.getBeanNamesForType(AdminImageGalleryController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AdminImageGalleryWebConfiguration.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MinioObjectBrowser.class)).isEmpty();
    }
}
