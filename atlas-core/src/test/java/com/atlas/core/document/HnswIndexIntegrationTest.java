package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
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
 * Confirms the vector-search infrastructure is in place: the HNSW index exists with the cosine
 * operator class it needs, and the cosine operator ({@code <=>}) the query relies on actually
 * executes against the embedding column.
 *
 * <p>Deliberately does <b>not</b> assert the query <i>plan</i> uses the index. On a handful of
 * seeded rows the Postgres planner correctly prefers a sequential scan (HNSW only pays off past a
 * data-size threshold, and can be gated further by {@code hnsw.ef_search}), so an EXPLAIN-based
 * "uses index" assertion would flake in CI against small fixtures without proving anything about
 * production. Asserting the index is defined with {@code vector_cosine_ops} is the durable,
 * non-brittle check: it guarantees the planner <i>can</i> choose it once the table is large enough.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HnswIndexIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class HnswIndexIntegrationTest {

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
  void hnswIndexExistsOnChunkEmbeddingWithCosineOperatorClass() {
    String indexDef =
        jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chunk_embedding_hnsw'",
            String.class);

    assertThat(indexDef).isNotNull();
    assertThat(indexDef).containsIgnoringCase("USING hnsw");
    assertThat(indexDef).contains("vector_cosine_ops");
    assertThat(indexDef).contains("embedding");
  }

  @Test
  void vectorSearchExecutesTheCosineOperatorAgainstTheEmbeddingColumn() {
    UUID documentId =
        jdbcTemplate.queryForObject(
            "INSERT INTO document (filename, content_hash, status) "
                + "VALUES ('idx.pdf', ?, 'READY') RETURNING id",
            UUID.class,
            "hash-" + UUID.randomUUID());
    jdbcTemplate.update(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
            + "embedding) VALUES (?, 0, 'governance policy', 1, 1, 10, CAST(? AS vector))",
        documentId,
        VectorLiteral.format(FakeEmbeddingService.vectorFor("governance policy")));

    // A 200 with a hit proves the ORDER BY embedding <=> CAST(? AS vector) query — the cosine
    // (vector_cosine_ops) operator — runs end to end against the column the index covers.
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/vector", new SearchRequest("governance", 5), SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isNotEmpty();
  }

  @TestConfiguration
  static class FakeEmbeddingConfig {
    @Bean
    FakeEmbeddingService embeddingService() {
      return new FakeEmbeddingService();
    }
  }
}
