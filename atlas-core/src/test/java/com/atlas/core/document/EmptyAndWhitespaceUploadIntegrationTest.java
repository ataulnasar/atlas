package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
 * A 0-byte file, or blank text/plain content, is rejected up front with 400 before any row insert
 * or file write. PDF/DOCX are deliberately NOT pre-validated for emptiness at upload time — that's
 * left to the parser — but a document that parses successfully into zero chunks must still never
 * reach READY: {@link IngestionProcessor} marks it FAILED instead.
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
  void zeroByteTxtUploadIsRejectedBeforeAnyRowIsCreated() {
    int documentsBefore = documentCount();

    ResponseEntity<ApiError> response =
        uploadExpecting("empty.txt", MediaType.TEXT_PLAIN, new byte[0], ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("empty_document");
    assertThat(documentCount()).isEqualTo(documentsBefore);
  }

  @Test
  void whitespaceOnlyTxtUploadIsRejectedBeforeAnyRowIsCreated() {
    byte[] content = "   \n\t\n   \n".getBytes(StandardCharsets.UTF_8);
    int documentsBefore = documentCount();

    ResponseEntity<ApiError> response =
        uploadExpecting("whitespace.txt", MediaType.TEXT_PLAIN, content, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("empty_document");
    assertThat(documentCount()).isEqualTo(documentsBefore);
  }

  @Test
  void aPdfThatParsesToNoTextLandsFailedRatherThanReady() throws IOException {
    byte[] blankPagePdf = buildPdfWithBlankPage();

    ResponseEntity<DocumentUploadResponse> uploadResponse =
        uploadExpecting(
            "blank-page.pdf",
            MediaType.APPLICATION_PDF,
            blankPagePdf,
            DocumentUploadResponse.class);
    assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID documentId = uploadResponse.getBody().id();

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.FAILED);

    assertThat(status.errorMessage()).isEqualTo("document produced no content");
    assertThat(status.chunkCount()).isZero();
    assertThat(chunkRowCount(documentId)).isZero();
  }

  private byte[] buildPdfWithBlankPage() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(buffer);
    }
    return buffer.toByteArray();
  }

  private int documentCount() {
    Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM document", Integer.class);
    return count != null ? count : 0;
  }

  private int chunkRowCount(UUID documentId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
    return count != null ? count : 0;
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
