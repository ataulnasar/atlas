package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
 * Exercises the full upload -> parse -> chunk -> persist pipeline over real HTTP against a
 * disposable pgvector/pgvector:pg16 Testcontainer, polling the status endpoint with Awaitility the
 * same way a real client would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestionProcessorIntegrationTest {

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
  void uploadedTxtDocumentReachesReadyWithChunksPersisted() {
    String content =
        "First paragraph with some real content to chunk.\n\n"
            + "Second paragraph with more content here to chunk as well.";
    UUID documentId =
        upload("notes.txt", MediaType.TEXT_PLAIN, content.getBytes(StandardCharsets.UTF_8));

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);

    assertThat(status.chunkCount()).isGreaterThan(0);
    assertThat(status.errorMessage()).isNull();

    List<Map<String, Object>> chunks =
        jdbcTemplate.queryForList(
            "SELECT start_page, end_page, token_count FROM chunk "
                + "WHERE document_id = ? ORDER BY chunk_index",
            documentId);
    assertThat(chunks).isNotEmpty();
    for (Map<String, Object> chunk : chunks) {
      assertThat(chunk.get("start_page")).isEqualTo(1);
      assertThat(chunk.get("end_page")).isEqualTo(1);
      assertThat((Integer) chunk.get("token_count")).isLessThanOrEqualTo(500);
    }
  }

  @Test
  void uploadedMultiPagePdfReachesReadyWithPageAwareChunks() throws IOException {
    byte[] pdfBytes =
        buildPdf(
            "Page one has some content to extract and chunk correctly.",
            "Page two has some different content to extract and chunk correctly.");
    UUID documentId = upload("report.pdf", MediaType.APPLICATION_PDF, pdfBytes);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);

    assertThat(status.chunkCount()).isGreaterThan(0);

    List<Map<String, Object>> chunks =
        jdbcTemplate.queryForList(
            "SELECT start_page, end_page, token_count FROM chunk "
                + "WHERE document_id = ? ORDER BY chunk_index",
            documentId);
    assertThat(chunks).isNotEmpty();
    for (Map<String, Object> chunk : chunks) {
      assertThat((Integer) chunk.get("start_page")).isBetween(1, 2);
      assertThat((Integer) chunk.get("end_page")).isBetween(1, 2);
      assertThat((Integer) chunk.get("token_count")).isLessThanOrEqualTo(500);
    }
    // At least one chunk should carry both pages' text given how short they are.
    assertThat(
            chunks.stream()
                .anyMatch(c -> c.get("start_page").equals(1) && c.get("end_page").equals(2)))
        .isTrue();
  }

  @Test
  void corruptPdfIsMarkedFailedWithNoChunksPersisted() throws IOException {
    byte[] validPdf = buildPdf("this document will be truncated to corrupt the file bytes");
    byte[] truncated = new byte[validPdf.length / 2];
    System.arraycopy(validPdf, 0, truncated, 0, truncated.length);

    UUID documentId = upload("corrupt.pdf", MediaType.APPLICATION_PDF, truncated);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.FAILED);

    assertThat(status.errorMessage()).isNotBlank();
    assertThat(status.chunkCount()).isEqualTo(0);

    Integer chunkRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
    assertThat(chunkRows).isEqualTo(0);
  }

  @Test
  void statusEndpointReturns404ForAnUnknownDocument() {
    ResponseEntity<ApiError> response =
        restTemplate.getForEntity("/api/documents/" + UUID.randomUUID(), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("document_not_found");
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

  private UUID upload(String filename, MediaType contentType, byte[] content) {
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

    ResponseEntity<DocumentUploadResponse> response =
        restTemplate.postForEntity(
            "/api/documents", new HttpEntity<>(body, requestHeaders), DocumentUploadResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
  }

  private byte[] buildPdf(String... pageTexts) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      for (String pageText : pageTexts) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
          contentStream.beginText();
          contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          contentStream.newLineAtOffset(50, 700);
          contentStream.showText(pageText);
          contentStream.endText();
        }
      }
      document.save(buffer);
    }
    return buffer.toByteArray();
  }
}
