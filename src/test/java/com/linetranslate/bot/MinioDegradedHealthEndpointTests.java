package com.linetranslate.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.linetranslate.bot.service.storage.MinioStorageService;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "openrouter.api.key=test-openrouter-key")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class MinioDegradedHealthEndpointTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @Test
    @SuppressWarnings("unchecked")
    void optionalMinioOutageIsVisibleButDoesNotBlockReadiness() {
        when(minioStorageService.isAvailable()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "DEGRADED");
        Map<String, Object> components =
                (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get("minio"))
                .containsEntry("status", "DEGRADED")
                .doesNotContainKey("details");
        assertThat(response.getBody().toString())
                .doesNotContain("test-openrouter-key", "signed", "exception", "user");
    }

    private ResponseEntity<Map<String, Object>> readiness() {
        return restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });
    }
}
