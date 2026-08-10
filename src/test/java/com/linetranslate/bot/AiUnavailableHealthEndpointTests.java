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
        properties = {"openai.api.key=", "gemini.api.key="})
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class AiUnavailableHealthEndpointTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @Test
    @SuppressWarnings("unchecked")
    void noConfiguredAiProviderBlocksReadinessWithoutExposingConfiguration() {
        when(minioStorageService.isAvailable()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        Map<String, Object> components =
                (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get("aiProvidersConfigured"))
                .containsEntry("status", "DOWN")
                .doesNotContainKey("details");
        assertThat((Map<String, Object>) components.get("openAiConfiguration"))
                .containsEntry("status", "DISABLED");
        assertThat((Map<String, Object>) components.get("geminiConfiguration"))
                .containsEntry("status", "DISABLED");
    }
}
