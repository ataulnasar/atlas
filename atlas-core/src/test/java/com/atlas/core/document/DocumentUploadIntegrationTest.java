package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises POST /api/documents end-to-end against a disposable pgvector/pgvector:pg16
 * Testcontainer — independent of the docker-compose stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "atlas.upload.max-file-size=1KB")
@Testcontainers
class DocumentUploadIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void uploadingASupportedFileCreatesAPendingDocument() {
    ResponseEntity<DocumentUploadResponse> response =
        upload("hello.txt", MediaType.TEXT_PLAIN, "hello world".getBytes(StandardCharsets.UTF_8));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().id()).isNotNull();
    assertThat(response.getBody().status()).isEqualTo(DocumentStatus.PENDING);
  }

  @Test
  void uploadingTheSameContentTwiceIsRejectedAsADuplicate() {
    byte[] content = "duplicate content".getBytes(StandardCharsets.UTF_8);
    ResponseEntity<DocumentUploadResponse> first =
        upload("first.txt", MediaType.TEXT_PLAIN, content);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID existingId = first.getBody().id();

    ResponseEntity<DuplicateDocumentError> second =
        uploadExpecting("second.txt", MediaType.TEXT_PLAIN, content, DuplicateDocumentError.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(second.getBody()).isNotNull();
    assertThat(second.getBody().existingDocumentId()).isEqualTo(existingId);
  }

  @Test
  void uploadingAnUnsupportedContentTypeIsRejected() {
    ResponseEntity<ApiError> response =
        uploadExpecting(
            "archive.zip",
            MediaType.valueOf("application/zip"),
            "not a document".getBytes(StandardCharsets.UTF_8),
            ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("unsupported_document_type");
  }

  @Test
  void uploadingAFileOverTheConfiguredMaxSizeIsRejected() {
    byte[] oversizedContent = new byte[2048]; // exceeds the 1KB test limit

    ResponseEntity<ApiError> response =
        uploadExpecting("big.txt", MediaType.TEXT_PLAIN, oversizedContent, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("document_too_large");
  }

  private ResponseEntity<DocumentUploadResponse> upload(
      String filename, MediaType contentType, byte[] content) {
    return uploadExpecting(filename, contentType, content, DocumentUploadResponse.class);
  }

  private <T> ResponseEntity<T> uploadExpecting(
      String filename, MediaType contentType, byte[] content, Class<T> responseType) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        };
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(contentType);

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(fileResource, fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    return restTemplate.postForEntity(
        "/api/documents", new HttpEntity<>(body, requestHeaders), responseType);
  }
}
