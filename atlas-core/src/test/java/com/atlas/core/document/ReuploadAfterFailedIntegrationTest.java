package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Verifies that re-uploading the exact same bytes after a document reaches FAILED retries it in
 * place: same document id, chunks re-ingested from scratch, final status READY. The first attempt
 * is failed via an injected one-shot transient read failure rather than genuinely corrupt content —
 * the retry path re-ingests the SAME bytes, so a truly corrupt file would just fail identically on
 * retry too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReuploadAfterFailedIntegrationTest.FlakyStorageConfig.class)
@Testcontainers
class ReuploadAfterFailedIntegrationTest {

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
  @Autowired private FlakyLocalFileStorageService flakyFileStorageService;

  @Test
  void reuploadingTheSameBytesAfterFailedRetriesInPlaceAndReachesReady() {
    byte[] content =
        "content whose first processing attempt fails transiently".getBytes(StandardCharsets.UTF_8);

    flakyFileStorageService.failNextRead();
    ResponseEntity<DocumentUploadResponse> firstUpload =
        uploadExpecting("first-attempt.txt", content, DocumentUploadResponse.class);
    assertThat(firstUpload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID documentId = firstUpload.getBody().id();

    DocumentStatusResponse failed = awaitStatus(documentId, DocumentStatus.FAILED);
    assertThat(failed.errorMessage()).isNotBlank();
    assertThat(failed.chunkCount()).isZero();

    ResponseEntity<DocumentUploadResponse> retryResponse =
        uploadExpecting("retry-attempt.txt", content, DocumentUploadResponse.class);

    assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getBody()).isNotNull();
    assertThat(retryResponse.getBody().id()).isEqualTo(documentId);

    DocumentStatusResponse ready = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(ready.errorMessage()).isNull();
    assertThat(ready.chunkCount()).isGreaterThan(0);

    Integer chunkRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
    assertThat(chunkRows).isEqualTo(ready.chunkCount());
  }

  private <T> ResponseEntity<T> uploadExpecting(
      String filename, byte[] content, Class<T> responseType) {
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

    return restTemplate.postForEntity(
        "/api/documents", new HttpEntity<>(body, requestHeaders), responseType);
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

  @TestConfiguration
  static class FlakyStorageConfig {
    @Bean
    @Primary
    FlakyLocalFileStorageService flakyLocalFileStorageService(StorageProperties storageProperties) {
      return new FlakyLocalFileStorageService(storageProperties);
    }
  }

  /** Fails exactly its next {@link #findStoredFile} call, then behaves normally again. */
  static class FlakyLocalFileStorageService extends LocalFileStorageService {
    private volatile boolean failNextRead;

    FlakyLocalFileStorageService(StorageProperties storageProperties) {
      super(storageProperties);
    }

    void failNextRead() {
      this.failNextRead = true;
    }

    @Override
    Optional<StoredFile> findStoredFile(UUID documentId) {
      if (failNextRead) {
        failNextRead = false;
        throw new UncheckedIOException(new IOException("Simulated transient storage read failure"));
      }
      return super.findStoredFile(documentId);
    }
  }
}
