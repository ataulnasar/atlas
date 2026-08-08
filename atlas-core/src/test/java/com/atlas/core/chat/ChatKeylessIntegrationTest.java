package com.atlas.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.ApiError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Keyless mode: with no API key configured (CI's default), no {@code ChatGenerator} bean exists, so
 * the chat endpoint reports itself unavailable rather than attempting to answer — unlike search,
 * which still works keyword-only. No fakes are imported here, so the context is exactly the keyless
 * production wiring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatKeylessIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void chatIs503WithAHintThatSearchRemainsAvailable() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/chat", new ChatRequest("Anything?"), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("generation_disabled");
    assertThat(response.getBody().message()).contains("/api/search");
  }

  @Test
  void blankQuestionIsStill400EvenKeyless() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/chat", new ChatRequest("   "), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("invalid_question");
  }

  @Test
  void streamChatIs503BeforeAnyStreamStarts() {
    // The 503 is a plain JSON response, decided before an SSE stream is ever opened.
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/chat/stream", new ChatRequest("Anything?"), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("generation_disabled");
    assertThat(response.getBody().message()).contains("/api/search");
  }
}
