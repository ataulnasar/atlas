package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Documents current behavior — not necessarily desired behavior — for re-uploading the exact same
 * bytes after the first attempt reaches FAILED. Duplicate detection keys on content_hash alone (see
 * {@code ux_document_content_hash} in V2__document_and_chunk_schema.sql), with no exemption for
 * FAILED rows, so today's answer is: it 409s against the failed row forever, with no way to retry
 * short of uploading different bytes. Whether failed documents should be re-ingestable is a product
 * decision this test surfaces rather than silently assumes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

  @Test
  void reuploadingTheSameBytesAfterFailedIsRejectedAsADuplicateOfTheFailedRow() throws IOException {
    byte[] validPdf = buildPdf("this document will be truncated to corrupt the file bytes");
    byte[] corrupt = new byte[validPdf.length / 2];
    System.arraycopy(validPdf, 0, corrupt, 0, corrupt.length);

    ResponseEntity<DocumentUploadResponse> firstUpload =
        uploadExpecting("corrupt-first.pdf", corrupt, DocumentUploadResponse.class);
    assertThat(firstUpload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID failedDocumentId = firstUpload.getBody().id();
    awaitStatus(failedDocumentId, DocumentStatus.FAILED);

    ResponseEntity<DuplicateDocumentError> retry =
        uploadExpecting("corrupt-retry.pdf", corrupt, DuplicateDocumentError.class);

    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(retry.getBody()).isNotNull();
    assertThat(retry.getBody().existingDocumentId()).isEqualTo(failedDocumentId);

    // The failed row itself is untouched by the rejected retry.
    DocumentStatusResponse stillFailed =
        restTemplate.getForObject(
            "/api/documents/" + failedDocumentId, DocumentStatusResponse.class);
    assertThat(stillFailed.status()).isEqualTo(DocumentStatus.FAILED);
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
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);

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
