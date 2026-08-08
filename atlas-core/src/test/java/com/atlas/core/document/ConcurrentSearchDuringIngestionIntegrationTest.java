package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Hybrid search fans its two legs out on the shared ingestion executor (see HybridSearchService),
 * so several concurrent hybrid searches during an in-flight ingestion contend for the same pool —
 * the executor-sharing risk flagged when hybrid was built. This proves it safe at demo scale: many
 * simultaneous searches all return correct results while a document ingests to completion, with no
 * deadlock, error, or lost ingestion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConcurrentSearchDuringIngestionIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class ConcurrentSearchDuringIngestionIntegrationTest {

  private static final int CONCURRENT_SEARCHES = 8;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentHybridSearchesSucceedWhileADocumentIsIngesting() throws Exception {
    // Pre-seed searchable content so hybrid has something to return.
    UUID corpusDoc = seedReadyDocument("corpus.pdf");
    for (int i = 0; i < 5; i++) {
      seedEmbeddedChunk(corpusDoc, i, "governance obligation clause " + i);
    }

    // Kick off an ingestion; it runs asynchronously on the shared executor.
    UUID ingestingDoc =
        upload(
            "ingesting.txt",
            "A newly uploaded document about governance and compliance obligations that is being "
                + "chunked and embedded on the shared ingestion executor while searches run.");

    // Fire many hybrid searches at once — each fans out vector+keyword on that same executor.
    ExecutorService clients = Executors.newFixedThreadPool(CONCURRENT_SEARCHES);
    try {
      List<CompletableFuture<HttpStatus>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_SEARCHES; i++) {
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  ResponseEntity<HybridSearchResponse> response =
                      restTemplate.postForEntity(
                          "/api/search/hybrid",
                          new SearchRequest("governance obligation", 10),
                          HybridSearchResponse.class);
                  // Touch the body so a malformed/empty response would surface here.
                  assertThat(response.getBody()).isNotNull();
                  assertThat(response.getBody().results()).isNotEmpty();
                  return HttpStatus.valueOf(response.getStatusCode().value());
                },
                clients));
      }

      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
      assertThat(futures).allSatisfy(f -> assertThat(f.join()).isEqualTo(HttpStatus.OK));
    } finally {
      clients.shutdownNow();
    }

    // And the ingestion that was contending for the same pool still completed.
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(150))
        .until(
            () ->
                restTemplate
                    .getForObject("/api/documents/" + ingestingDoc, DocumentStatusResponse.class)
                    .status(),
            status -> status == DocumentStatus.READY);
  }

  private UUID upload(String filename, String content) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
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

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  private void seedEmbeddedChunk(UUID documentId, int chunkIndex, String content) {
    jdbcTemplate.update(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
            + "embedding) VALUES (?, ?, ?, 1, 1, 10, CAST(? AS vector))",
        documentId,
        chunkIndex,
        content,
        VectorLiteral.format(FakeEmbeddingService.vectorFor(content)));
  }

  @TestConfiguration
  static class FakeEmbeddingConfig {
    @Bean
    FakeEmbeddingService embeddingService() {
      return new FakeEmbeddingService();
    }
  }
}
