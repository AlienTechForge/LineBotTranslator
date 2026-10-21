package com.linetranslate.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.linetranslate.bot.service.storage.MinioStorageService;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "openrouter.api.key=test-openrouter-key")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class HealthEndpointIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private HealthEndpointGroups groups;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void storageIsAvailable() {
        when(minioStorageService.isAvailable()).thenReturn(true);
    }

    @Test
    void livenessReportsProcessHealthWithoutDetails() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/actuator/health/liveness",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .doesNotContainKeys("components", "details");
    }

    @Test
    void readinessReportsRequiredDependencyHealthWithoutDetails() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertComponentStatus(response, "lineConfiguration", "UP");
        assertComponentStatus(response, "mongo", "UP");
        assertComponentStatus(response, "minio", "UP");
        assertComponentStatus(response, "openRouterConfiguration", "UP");
        assertNoDetails(response.getBody());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theImageTranslatorIsNotPartOfReadinessSoADeadServiceNeverStopsTextMessages() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() { });

        assertThat((Map<String, Object>) response.getBody().get("components"))
                .doesNotContainKeys("imageTranslator", "ocrConfiguration");
    }

    @Test
    void theImageTranslatorIsAMemberOfTheLivenessGroupOnly() {
        assertThat(groups.get("liveness").isMember("imageTranslator")).isTrue();
        assertThat(groups.get("readiness").isMember("imageTranslator")).isFalse();
        assertThat(groups.get("readiness").isMember("ocrConfiguration")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void assertComponentStatus(
            ResponseEntity<Map<String, Object>> response,
            String component,
            String status) {
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get(component)).containsEntry("status", status);
    }

    @SuppressWarnings("unchecked")
    private void assertNoDetails(Object value) {
        if (value instanceof Map<?, ?> map) {
            assertThat(map.containsKey("details")).isFalse();
            map.values().forEach(this::assertNoDetails);
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(this::assertNoDetails);
        }
    }
}
