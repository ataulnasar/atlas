package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.atlas.core.embedding.FakeEmbeddingService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * End-to-end embedding-pipeline behaviour with a deterministic fake provider (no API key, no
 * network — the whole point being that this stays green in CI). Small chunking budget and batch
 * size so a modest document produces several chunks across multiple embedding batches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChunkEmbeddingIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class ChunkEmbeddingIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
    // Force several chunks and several embedding batches out of a short document.
    registry.add("atlas.chunking.max-tokens", () -> "40");
    registry.add("atlas.chunking.overlap-tokens", () -> "10");
    registry.add("atlas.embedding.batch-size", () -> "2");
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FakeEmbeddingService fakeEmbeddingService;

  @BeforeEach
  void resetFake() {
    fakeEmbeddingService.setFailing(false);
  }

  @Test
  void uploadedDocumentChunksAllReceiveNonNull1536DimEmbeddings() {
    UUID documentId = uploadTxt("embedded.txt", multiParagraphContent("happy-path"));

    DocumentStatusResponse status = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(status.chunkCount()).isGreaterThan(1);

    assertThat(nullEmbeddingCount(documentId)).isZero();
    List<Integer> dims =
        jdbcTemplate.queryForList(
            "SELECT vector_dims(embedding) FROM chunk WHERE document_id = ? ORDER BY chunk_index",
            Integer.class,
            documentId);
    assertThat(dims).isNotEmpty().allMatch(d -> d == 1536);

    // Distinct chunk content produced distinct vectors, as a real provider would: the count of
    // distinct embeddings matches the count of distinct chunk contents (the fake is a deterministic
    // function of content, so identical chunks — which the chunker can emit — share a vector).
    Integer distinctVectors =
        jdbcTemplate.queryForObject(
            "SELECT count(DISTINCT embedding) FROM chunk WHERE document_id = ?",
            Integer.class,
            documentId);
    Integer distinctContents =
        jdbcTemplate.queryForObject(
            "SELECT count(DISTINCT content) FROM chunk WHERE document_id = ?",
            Integer.class,
            documentId);
    assertThat(distinctVectors).isGreaterThan(1).isEqualTo(distinctContents);
  }

  @Test
  void embeddingFailureMarksDocumentFailedWithPrefixedMessageThenRetrySucceeds() {
    byte[] content = multiParagraphContent("retry-path").getBytes(StandardCharsets.UTF_8);

    fakeEmbeddingService.setFailing(true);
    UUID documentId = uploadTxt("retry-me.txt", content);

    DocumentStatusResponse failed = awaitStatus(documentId, DocumentStatus.FAILED);
    assertThat(failed.errorMessage()).startsWith("embedding failed:");
    // Ingestion inserts chunks before embedding; the failed attempt leaves them un-embedded.
    assertThat(nullEmbeddingCount(documentId)).isEqualTo(failed.chunkCount());

    // Re-upload identical bytes retries in place under the same id (see ReuploadAfterFailed...).
    fakeEmbeddingService.setFailing(false);
    ResponseEntity<DocumentUploadResponse> retry =
        uploadExpecting("retry-me-again.txt", content, DocumentUploadResponse.class);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getBody().id()).isEqualTo(documentId);

    DocumentStatusResponse ready = awaitStatus(documentId, DocumentStatus.READY);
    assertThat(ready.errorMessage()).isNull();
    assertThat(ready.chunkCount()).isGreaterThan(0);
    assertThat(nullEmbeddingCount(documentId)).isZero();
  }

  @Test
  void backfillEmbedsAllNullChunksOfReadyDocumentsAndReportsCounts() {
    UUID docA = seedReadyDocumentWithNullChunks("seed-a", 3);
    UUID docB = seedReadyDocumentWithNullChunks("seed-b", 2);

    ResponseEntity<EmbeddingBackfillResponse> response =
        restTemplate.postForEntity(
            "/api/admin/embeddings/backfill", null, EmbeddingBackfillResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().chunksEmbedded()).isEqualTo(5);
    assertThat(response.getBody().documentsTouched()).isEqualTo(2);

    assertThat(nullEmbeddingCount(docA)).isZero();
    assertThat(nullEmbeddingCount(docB)).isZero();
    List<Integer> dims =
        jdbcTemplate.queryForList(
            "SELECT vector_dims(embedding) FROM chunk WHERE document_id IN (?, ?)",
            Integer.class,
            docA,
            docB);
    assertThat(dims).hasSize(5).allMatch(d -> d == 1536);
  }

  @Test
  void backfillIsIdempotentWhenNothingIsMissing() {
    seedReadyDocumentWithNullChunks("seed-once", 2);
    restTemplate.postForEntity(
        "/api/admin/embeddings/backfill", null, EmbeddingBackfillResponse.class);

    ResponseEntity<EmbeddingBackfillResponse> second =
        restTemplate.postForEntity(
            "/api/admin/embeddings/backfill", null, EmbeddingBackfillResponse.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().chunksEmbedded()).isZero();
    assertThat(second.getBody().documentsTouched()).isZero();
  }

  private int nullEmbeddingCount(UUID documentId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk WHERE document_id = ? AND embedding IS NULL",
            Integer.class,
            documentId);
    return count != null ? count : 0;
  }

  private UUID seedReadyDocumentWithNullChunks(String name, int chunkCount) {
    UUID documentId =
        jdbcTemplate.queryForObject(
            "INSERT INTO document (filename, content_hash, status) "
                + "VALUES (?, ?, 'READY') RETURNING id",
            UUID.class,
            name + ".txt",
            "hash-" + name + "-" + UUID.randomUUID());
    for (int i = 0; i < chunkCount; i++) {
      jdbcTemplate.update(
          "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count) "
              + "VALUES (?, ?, ?, 1, 1, 10)",
          documentId,
          i,
          "Seeded chunk " + i + " for document " + name + " with some distinct body text.");
    }
    return documentId;
  }

  private String multiParagraphContent(String salt) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      builder
          .append("Paragraph ")
          .append(i)
          .append(" of the ")
          .append(salt)
          .append(" document contains a few sentences of distinct filler content so the chunker")
          .append(" produces multiple separate chunks. Each paragraph is its own topic.\n\n");
    }
    return builder.toString();
  }

  private UUID uploadTxt(String filename, String content) {
    return uploadTxt(filename, content.getBytes(StandardCharsets.UTF_8));
  }

  private UUID uploadTxt(String filename, byte[] content) {
    ResponseEntity<DocumentUploadResponse> response =
        uploadExpecting(filename, content, DocumentUploadResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
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
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                restTemplate.getForObject(
                    "/api/documents/" + documentId, DocumentStatusResponse.class),
            response -> response.status() == expected);
  }

  @TestConfiguration
  static class FakeEmbeddingConfig {
    // A single bean, typed as the concrete fake: it satisfies ObjectProvider<EmbeddingService> (so
    // the pipeline uses it) while the test autowires the same instance to drive its failure toggle.
    // Two separate beans of type EmbeddingService would make the provider ambiguous.
    @Bean
    FakeEmbeddingService embeddingService() {
      return new FakeEmbeddingService();
    }
  }
}
