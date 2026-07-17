package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
 * Ingests a synthetic multi-page PDF large enough to produce 50+ chunks (well past the single-group
 * case the other PDF tests exercise) and checks the two invariants that only show up at that scale:
 * a contiguous {@code chunk_index} sequence, and page provenance that never runs backwards relative
 * to chunk order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LargeDocumentChunkingIntegrationTest {

  private static final int PAGE_COUNT = 40;

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
  void largeMultiPagePdfProducesContiguousChunkIndicesAndNonDecreasingPageProvenance()
      throws IOException {
    byte[] pdfBytes = buildLargePdf(PAGE_COUNT);
    UUID documentId = upload("large-report.pdf", pdfBytes);

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(status.errorMessage()).isNull();
    assertThat(status.chunkCount()).isGreaterThanOrEqualTo(50);

    List<Map<String, Object>> chunks =
        jdbcTemplate.queryForList(
            "SELECT chunk_index, start_page, end_page FROM chunk "
                + "WHERE document_id = ? ORDER BY chunk_index",
            documentId);
    assertThat(chunks).hasSize(status.chunkCount());

    List<Integer> chunkIndices = chunks.stream().map(c -> (Integer) c.get("chunk_index")).toList();
    List<Integer> expectedIndices = IntStream.range(0, chunks.size()).boxed().toList();
    assertThat(chunkIndices).containsExactlyElementsOf(expectedIndices);

    int previousStartPage = Integer.MIN_VALUE;
    int previousEndPage = Integer.MIN_VALUE;
    for (Map<String, Object> chunk : chunks) {
      int startPage = (Integer) chunk.get("start_page");
      int endPage = (Integer) chunk.get("end_page");
      assertThat(startPage).isBetween(1, PAGE_COUNT);
      assertThat(endPage).isBetween(1, PAGE_COUNT);
      assertThat(startPage).isLessThanOrEqualTo(endPage);
      assertThat(startPage).isGreaterThanOrEqualTo(previousStartPage);
      assertThat(endPage).isGreaterThanOrEqualTo(previousEndPage);
      previousStartPage = startPage;
      previousEndPage = endPage;
    }
  }

  private byte[] buildLargePdf(int pageCount) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      for (int p = 1; p <= pageCount; p++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
          contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
          float y = 750;
          for (int line = 0; line < 45 && y > 40; line++) {
            contentStream.beginText();
            contentStream.newLineAtOffset(40, y);
            contentStream.showText(
                "Page "
                    + p
                    + " line "
                    + line
                    + ": filler sentence providing enough distinct token content to force many"
                    + " chunk boundaries across this synthetic large document used for testing.");
            contentStream.endText();
            y -= 14;
          }
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
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(150))
        .until(
            () ->
                restTemplate.getForObject(
                    "/api/documents/" + documentId, DocumentStatusResponse.class),
            response -> response.status() == expected);
  }
}
