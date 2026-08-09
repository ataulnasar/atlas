package com.atlas.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.ApiError;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-stack auth wiring with ATLAS_API_KEY set: the filter guards /api/**, actuator health stays
 * open. Keyless (disabled) mode is exercised end-to-end by the entire rest of the suite, which runs
 * with no ATLAS_API_KEY and hits /api/** without the header, never getting 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiKeyAuthIntegrationTest {

  // A fake key for this test only — not a real secret.
  private static final String API_KEY = "integration-test-key-8f2a"; // gitleaks:allow

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
    registry.add("ATLAS_API_KEY", () -> API_KEY);
  }

  @Autowired private TestRestTemplate restTemplate;

  private ResponseEntity<ApiError> getConversation(String keyHeader) {
    HttpHeaders headers = new HttpHeaders();
    if (keyHeader != null) {
      headers.add(ApiKeyAuthFilter.API_KEY_HEADER, keyHeader);
    }
    return restTemplate.exchange(
        "/api/conversations/" + UUID.randomUUID(),
        HttpMethod.GET,
        new HttpEntity<>(headers),
        ApiError.class);
  }

  @Test
  void apiRouteWithoutKeyIs401() {
    ResponseEntity<ApiError> response = getConversation(null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("unauthorized");
  }

  @Test
  void apiRouteWithWrongKeyIs401() {
    assertThat(getConversation("wrong-key").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void apiRouteWithCorrectKeyPassesAuth() {
    // Passes the filter, then the endpoint 404s the random id — proving auth let it through.
    ResponseEntity<ApiError> response = getConversation(API_KEY);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("conversation_not_found");
  }

  @Test
  void actuatorHealthStaysOpenWithoutKey() {
    ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody()).contains("UP");
  }
}
