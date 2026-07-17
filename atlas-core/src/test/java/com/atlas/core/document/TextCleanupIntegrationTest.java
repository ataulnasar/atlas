package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * Configures {@code atlas.parsing.strip-line-patterns} to match a synthetic per-page footer,
 * ingests a PDF that injects that exact footer line on every page, and asserts no persisted chunk
 * contains it — the end-to-end proof that {@link com.atlas.core.ingestion.TextCleanupService} is
 * actually wired into the ingestion pipeline, not just unit-tested in isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TextCleanupIntegrationTest {

  private static final String FOOTER_MARKER = "SYNTHETIC-FOOTER-MARK-2024";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
    // Whole-line "contains" match (rather than an exact ^...$ anchor) since PDF text extraction
    // may leave trailing whitespace on an otherwise-isolated footer line.
    registry.add("atlas.parsing.strip-line-patterns", () -> ".*" + FOOTER_MARKER + ".*");
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void injectedPerPageFooterIsStrippedFromEveryPersistedChunk() throws IOException {
    byte[] pdfBytes =
        buildPdfWithFooter(
            "Page one carries substantive body text about a first distinct legal topic that "
                + "should be long enough to survive as real chunk content on its own.",
            "Page two carries substantive body text about a second distinct legal topic that "
                + "should be long enough to survive as real chunk content on its own.",
            "Page three carries substantive body text about a third distinct legal topic that "
                + "should be long enough to survive as real chunk content on its own.");

    UUID documentId = upload("footer-injected.pdf", pdfBytes);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(status.chunkCount()).isGreaterThan(0);

    List<String> chunkContents =
        jdbcTemplate.queryForList(
            "SELECT content FROM chunk WHERE document_id = ?", String.class, documentId);
    assertThat(chunkContents).isNotEmpty();
    for (String content : chunkContents) {
      assertThat(content).doesNotContain(FOOTER_MARKER);
    }
  }

  private byte[] buildPdfWithFooter(String... pageBodies) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      for (String body : pageBodies) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
          contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
          contentStream.beginText();
          contentStream.newLineAtOffset(50, 700);
          contentStream.showText(body);
          contentStream.endText();

          // A footer line at the bottom of the page, standalone — exactly what a real EUR-Lex
          // page footer looks like once extracted: an isolated line sitting between page text.
          contentStream.beginText();
          contentStream.newLineAtOffset(50, 50);
          contentStream.showText(FOOTER_MARKER);
          contentStream.endText();
        }
      }
      document.save(buffer);
    }
    return buffer.toByteArray();
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
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);

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
