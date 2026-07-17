package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A storage failure after the document row insert must not leave an orphaned row: its content_hash
 * would permanently block re-uploading the same content via the 409 duplicate path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DocumentUploadTransactionRollbackTest.FlakyStorageConfig.class)
@Testcontainers
class DocumentUploadTransactionRollbackTest {

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
  @Autowired private FlakyLocalFileStorageService flakyFileStorageService;

  @Test
  void aStorageFailureRollsBackTheDocumentRowSoTheSameContentCanBeUploadedAgain() {
    byte[] content = "content whose first storage attempt fails".getBytes(StandardCharsets.UTF_8);

    flakyFileStorageService.failNextCall();
    ResponseEntity<String> failedResponse = upload("first-try.txt", content, String.class);
    assertThat(failedResponse.getStatusCode().is5xxServerError()).isTrue();

    ResponseEntity<DocumentUploadResponse> retryResponse =
        upload("second-try.txt", content, DocumentUploadResponse.class);

    assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retryResponse.getBody()).isNotNull();
    assertThat(retryResponse.getBody().status()).isEqualTo(DocumentStatus.PENDING);
  }

  private <T> ResponseEntity<T> upload(String filename, byte[] content, Class<T> responseType) {
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

  @TestConfiguration
  static class FlakyStorageConfig {
    @Bean
    @Primary
    FlakyLocalFileStorageService flakyLocalFileStorageService(StorageProperties storageProperties) {
      return new FlakyLocalFileStorageService(storageProperties);
    }
  }

  /** Fails exactly its next {@link #store} call, then behaves normally again. */
  static class FlakyLocalFileStorageService extends LocalFileStorageService {
    private volatile boolean failNextCall;

    FlakyLocalFileStorageService(StorageProperties storageProperties) {
      super(storageProperties);
    }

    void failNextCall() {
      this.failNextCall = true;
    }

    @Override
    Path store(UUID documentId, SupportedContentType contentType, MultipartFile file) {
      if (failNextCall) {
        failNextCall = false;
        throw new UncheckedIOException(new IOException("Simulated storage failure"));
      }
      return super.store(documentId, contentType, file);
    }
  }
}
