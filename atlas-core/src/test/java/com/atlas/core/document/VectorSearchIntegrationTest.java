package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import java.nio.file.Path;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Vector search over pgvector with the deterministic {@link FakeEmbeddingService}: chunk embeddings
 * are seeded to exactly match (or deliberately differ from) what a query embeds to, so similarity
 * ordering is fully predictable without a real provider. The tables are truncated before each test
 * so counts are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VectorSearchIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class VectorSearchIntegrationTest {

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

  @BeforeEach
  void clean() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
  }

  @Test
  void exactMatchRanksFirstAndResultsAreOrderedByDescendingScore() {
    UUID documentId = seedReadyDocument("corpus.txt");
    for (int i = 0; i < 5; i++) {
      seedEmbeddedChunk(documentId, i, "topic-" + i + " body text", "topic-" + i + " body text");
    }

    VectorSearchResponse body = search("topic-2 body text", 5);

    List<VectorSearchHit> results = body.results();
    assertThat(results).hasSize(5);
    // The chunk whose stored embedding equals the query's embedding is the top hit at score ~1.0.
    assertThat(results.get(0).snippet()).contains("topic-2");
    assertThat(results.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
    // pgvector's ORDER BY guarantees non-increasing similarity down the list.
    for (int i = 1; i < results.size(); i++) {
      assertThat(results.get(i).score()).isLessThanOrEqualTo(results.get(i - 1).score());
    }
    // Citation metadata is populated.
    assertThat(results.get(0).documentId()).isEqualTo(documentId);
    assertThat(results.get(0).documentFilename()).isEqualTo("corpus.txt");
    assertThat(results.get(0).chunkIndex()).isBetween(0, 4);
  }

  @Test
  void topKLimitsTheNumberOfResults() {
    UUID documentId = seedReadyDocument("small.txt");
    for (int i = 0; i < 5; i++) {
      seedEmbeddedChunk(documentId, i, "chunk " + i, "chunk " + i);
    }

    assertThat(search("chunk 1", 2).results()).hasSize(2);
  }

  @Test
  void topKIsCappedAtFifty() {
    UUID documentId = seedReadyDocument("big.txt");
    for (int i = 0; i < 51; i++) {
      seedEmbeddedChunk(documentId, i, "row " + i, "row " + i);
    }

    // Request well beyond the cap; the endpoint must clamp to 50 even though 51 chunks match.
    assertThat(search("row 0", 1000).results()).hasSize(50);
  }

  @Test
  void nullEmbeddedChunksAreExcludedFromResults() {
    UUID documentId = seedReadyDocument("mixed.txt");
    UUID embeddedA = seedEmbeddedChunk(documentId, 0, "alpha", "alpha");
    UUID embeddedB = seedEmbeddedChunk(documentId, 1, "beta", "beta");
    UUID nullChunk1 = seedNullEmbeddingChunk(documentId, 2, "gamma");
    UUID nullChunk2 = seedNullEmbeddingChunk(documentId, 3, "delta");

    VectorSearchResponse body = search("alpha", 100);

    List<UUID> returnedIds = body.results().stream().map(VectorSearchHit::chunkId).toList();
    assertThat(returnedIds).containsExactlyInAnyOrder(embeddedA, embeddedB);
    assertThat(returnedIds).doesNotContain(nullChunk1, nullChunk2);
  }

  @Test
  void snippetIsCappedToTheLeadingSliceOfContent() {
    UUID documentId = seedReadyDocument("long.txt");
    String longContent = "X".repeat(1000);
    seedEmbeddedChunk(documentId, 0, longContent, "the-only-topic");

    List<VectorSearchHit> results = search("the-only-topic", 10).results();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).snippet()).hasSize(300).isEqualTo("X".repeat(300));
  }

  private VectorSearchResponse search(String query, int topK) {
    ResponseEntity<VectorSearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/vector", new VectorSearchRequest(query, topK), VectorSearchResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  /** Seeds a chunk whose stored embedding is exactly the fake's vector for {@code embedText}. */
  private UUID seedEmbeddedChunk(
      UUID documentId, int chunkIndex, String content, String embedText) {
    String literal = VectorLiteral.format(FakeEmbeddingService.vectorFor(embedText));
    return jdbcTemplate.queryForObject(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
            + "embedding) VALUES (?, ?, ?, 1, 1, 10, CAST(? AS vector)) RETURNING id",
        UUID.class,
        documentId,
        chunkIndex,
        content,
        literal);
  }

  private UUID seedNullEmbeddingChunk(UUID documentId, int chunkIndex, String content) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count) "
            + "VALUES (?, ?, ?, 1, 1, 10) RETURNING id",
        UUID.class,
        documentId,
        chunkIndex,
        content);
  }

  @TestConfiguration
  static class FakeEmbeddingConfig {
    @Bean
    FakeEmbeddingService embeddingService() {
      return new FakeEmbeddingService();
    }
  }
}
