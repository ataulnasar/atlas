package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.EmbeddingService;
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
 * End-to-end hybrid search. The embedding fake returns one fixed query vector {@code Q = [1,0,…]},
 * and each seeded chunk's embedding is crafted with a known cosine to {@code Q} ({@code [c,
 * √(1-c²), 0…]}), so vector ranks are fully controlled — and keyword ranks are controlled by term
 * frequency. That lets a test construct the classic RRF property (mid-in-both beats top-of-one)
 * deterministically. Tables are truncated before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HybridSearchIntegrationTest.FixedQueryEmbeddingConfig.class)
@Testcontainers
class HybridSearchIntegrationTest {

  private static final int DIMENSIONS = 1536;

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
  void aChunkRankedMidInBothSourcesOutranksAChunkTopOfOnlyOne() {
    UUID documentId = seedReadyDocument("policy.txt");
    // vectorTopOnly: closest to Q (vector rank 1), but no "compliance" term (absent from keyword).
    UUID vectorTopOnly =
        seedEmbeddedChunk(
            documentId, 0, "Governance and oversight duties without the target term.", 0.99);
    // bothMid: 2nd-closest to Q (vector rank 2) and mentions the term once (keyword rank 2).
    UUID bothMid =
        seedEmbeddedChunk(
            documentId, 1, "A short note mentioning compliance among other topics.", 0.90);
    // keywordTopOnly: no embedding (absent from vector), but term-dense (keyword rank 1).
    UUID keywordTopOnly =
        seedNullEmbeddingChunk(
            documentId,
            2,
            "Compliance compliance compliance compliance compliance obligations and controls.");

    List<HybridSearchHit> results = hybridSearch("compliance", 10);

    // The classic RRF property: mid-in-both (1/62 + 1/62) beats either single-list #1 (1/61).
    assertThat(results.get(0).chunkId()).isEqualTo(bothMid);

    HybridSearchHit both = byId(results, bothMid);
    assertThat(both.foundBy()).isEqualTo("both");
    assertThat(both.vectorRank()).isEqualTo(2);
    assertThat(both.keywordRank()).isEqualTo(2);

    HybridSearchHit vectorHit = byId(results, vectorTopOnly);
    assertThat(vectorHit.foundBy()).isEqualTo("vector");
    assertThat(vectorHit.vectorRank()).isEqualTo(1);
    assertThat(vectorHit.keywordRank()).isNull();

    HybridSearchHit keywordHit = byId(results, keywordTopOnly);
    assertThat(keywordHit.foundBy()).isEqualTo("keyword");
    assertThat(keywordHit.vectorRank()).isNull();
    assertThat(keywordHit.keywordRank()).isEqualTo(1);

    assertThat(both.score()).isGreaterThan(vectorHit.score()).isGreaterThan(keywordHit.score());
  }

  @Test
  void topKLimitsTheNumberOfFusedResults() {
    UUID documentId = seedReadyDocument("many.txt");
    double cosine = 0.95;
    for (int i = 0; i < 4; i++) {
      seedEmbeddedChunk(
          documentId, i, "Chunk " + i + " about governance and governance policy.", cosine);
      cosine -= 0.1;
    }

    assertThat(hybridSearch("governance", 2)).hasSize(2);
  }

  @Test
  void aBlankQueryIsRejected() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/search/hybrid", new SearchRequest("   ", 10), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("invalid_query");
  }

  private List<HybridSearchHit> hybridSearch(String query, int topK) {
    ResponseEntity<HybridSearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/hybrid", new SearchRequest(query, topK), HybridSearchResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().results();
  }

  private static HybridSearchHit byId(List<HybridSearchHit> hits, UUID chunkId) {
    return hits.stream()
        .filter(hit -> hit.chunkId().equals(chunkId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("chunk " + chunkId + " not in results"));
  }

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  /** Seeds a chunk whose embedding has cosine {@code cosineToQuery} to the fixed query vector Q. */
  private UUID seedEmbeddedChunk(
      UUID documentId, int chunkIndex, String content, double cosineToQuery) {
    String literal = VectorLiteral.format(embeddingWithCosine(cosineToQuery));
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

  // Q = [1, 0, 0, ...]; an embedding [c, sqrt(1-c^2), 0, ...] has unit norm and cosine c to Q.
  private static float[] embeddingWithCosine(double cosine) {
    float[] vector = new float[DIMENSIONS];
    vector[0] = (float) cosine;
    vector[1] = (float) Math.sqrt(1 - cosine * cosine);
    return vector;
  }

  private static float[] fixedQueryVector() {
    float[] vector = new float[DIMENSIONS];
    vector[0] = 1f;
    return vector;
  }

  @TestConfiguration
  static class FixedQueryEmbeddingConfig {
    @Bean
    EmbeddingService embeddingService() {
      // Returns the same fixed query vector Q regardless of input text — the query embedding is the
      // only thing embedded at search time, and fixing it lets the test control vector ranks via
      // the seeded chunk embeddings' cosine to Q.
      return texts -> texts.stream().map(text -> fixedQueryVector()).toList();
    }
  }
}
