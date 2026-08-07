package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Keyless degraded mode: with no API key configured the {@link com.atlas.core.embedding
 * .EmbeddingService} bean doesn't exist, so ingestion must still reach READY (leaving embeddings
 * NULL and logging the WARN once), and the backfill endpoint must refuse with 503. This mirrors CI,
 * where no key is ever present — so no fake embedding bean is registered here on purpose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeylessEmbeddingIntegrationTest {

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
  @Autowired private JdbcTemplate jdbcTemplate;

  private Logger ingestionLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogAppender() {
    ingestionLogger = (Logger) LoggerFactory.getLogger(IngestionProcessor.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    ingestionLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    ingestionLogger.detachAppender(logAppender);
  }

  @Test
  void withoutAProviderDocumentReachesReadyWithNullEmbeddingsAndWarnsOnce() {
    UUID documentId = upload("keyless.txt", "Some content to chunk and store without embeddings.");

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(status.errorMessage()).isNull();
    assertThat(status.chunkCount()).isGreaterThan(0);

    Integer embeddedChunks =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ? AND embedding IS NOT NULL",
            Integer.class,
            documentId);
    assertThat(embeddedChunks).isZero();

    long warnCount =
        logAppender.list.stream()
            .filter(
                event ->
                    event
                        .getFormattedMessage()
                        .contains("embedding disabled — no provider configured"))
            .count();
    assertThat(warnCount).isEqualTo(1);
  }

  @Test
  void backfillIsUnavailableWithoutAProvider() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/admin/embeddings/backfill", null, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("embedding_disabled");
  }

  @Test
  void vectorSearchIsUnavailableWithoutAProvider() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/search/vector", new VectorSearchRequest("anything", 10), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("embedding_disabled");
  }

  private UUID upload(String filename, String content) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return filename;
          }
        };
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.TEXT_PLAIN);

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(fileResource, fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    ResponseEntity<DocumentUploadResponse> response =
        restTemplate.postForEntity(
            "/api/documents", new HttpEntity<>(body, requestHeaders), DocumentUploadResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
  }

  private DocumentStatusResponse awaitStatus(UUID documentId, DocumentStatus expected) {
    return await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                restTemplate.getForObject(
                    "/api/documents/" + documentId, DocumentStatusResponse.class),
            response -> response.status() == expected);
  }
}
