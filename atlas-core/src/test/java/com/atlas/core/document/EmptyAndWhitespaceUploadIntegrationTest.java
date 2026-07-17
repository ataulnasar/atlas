package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Documents current (not necessarily desired) behavior for degenerate TXT uploads. Neither the
 * upload path nor {@code ChunkingService} rejects blank content — a 0-byte file and a
 * whitespace-only file both pass validation, get parsed into a single empty/blank page, and {@code
 * ChunkingService#chunk} returns no candidates for blank {@code workingText}. Since {@code
 * IngestionProcessor} always calls {@code markReady} after a chunking pass that didn't throw, both
 * cases land on READY with zero chunks rather than being rejected outright or marked FAILED.
 * Whether "empty document" should instead be a rejection (400) is a product decision this test
 * surfaces rather than silently assumes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EmptyAndWhitespaceUploadIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerStorageProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void zeroByteTxtUploadReachesReadyWithZeroChunks() {
    UUID documentId = upload("empty.txt", new byte[0]);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);

    assertThat(status.errorMessage()).isNull();
    assertThat(status.chunkCount()).isZero();
    assertThat(chunkRowCount(documentId)).isZero();
  }

  @Test
  void whitespaceOnlyTxtUploadReachesReadyWithZeroChunks() {
    byte[] content = "   \n\t\n   \n".getBytes(StandardCharsets.UTF_8);
    UUID documentId = upload("whitespace.txt", content);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);

    assertThat(status.errorMessage()).isNull();
    assertThat(status.chunkCount()).isZero();
    assertThat(chunkRowCount(documentId)).isZero();
  }

  private int chunkRowCount(UUID documentId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
    return count != null ? count : 0;
  }

  private UUID upload(String filename, byte[] content) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content) {
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
