package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import java.nio.file.Path;
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
 * Degenerate and boundary inputs must not throw. Covers a very long query (the embedding-truncation
 * path), a query of only punctuation and emoji, and topK boundary values. The topK cap at 50 (and
 * 51 → 50) is already covered per-engine by the vector/keyword/keyless suites; this fills the
 * remaining boundary (0 and 1) and the malformed-content-that-shouldn't-500 cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SearchEdgeInputsIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class SearchEdgeInputsIntegrationTest {

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
  void seed() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    UUID documentId = seedReadyDocument("edge.pdf");
    for (int i = 0; i < 3; i++) {
      seedEmbeddedChunk(documentId, i, "governance compliance obligation clause " + i);
    }
  }

  @Test
  void aVeryLongQueryIsHandledWithoutError() {
    // ~5,000 words. A real provider truncates to its token limit; the fake embeds any length. The
    // point is that the endpoint accepts and processes it rather than erroring on the long input.
    String longQuery = ("governance compliance obligation ".repeat(1500)).strip();

    for (String engine : new String[] {"vector", "keyword", "hybrid"}) {
      ResponseEntity<String> response =
          restTemplate.postForEntity(
              "/api/search/" + engine, new SearchRequest(longQuery, 5), String.class);
      assertThat(response.getStatusCode()).as("engine=%s", engine).isEqualTo(HttpStatus.OK);
    }
  }

  @Test
  void aPunctuationAndEmojiOnlyQueryReturnsCleanlyNot500() {
    String query = "!@#$%^&*()_+ 🚀🔥✨";

    // Vector/hybrid embed it and rank; keyword's tsquery reduces to empty → no matches. All three
    // must be a clean 200 (an empty result set is fine), never a 500.
    ResponseEntity<SearchResponse> keyword =
        restTemplate.postForEntity(
            "/api/search/keyword", new SearchRequest(query, 5), SearchResponse.class);
    assertThat(keyword.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(keyword.getBody().results()).isEmpty();

    for (String engine : new String[] {"vector", "hybrid"}) {
      ResponseEntity<String> response =
          restTemplate.postForEntity(
              "/api/search/" + engine, new SearchRequest(query, 5), String.class);
      assertThat(response.getStatusCode()).as("engine=%s", engine).isEqualTo(HttpStatus.OK);
    }
  }

  @Test
  void topKOfZeroFloorsToOneRatherThanErroring() {
    // Documented behaviour: normalizeTopK floors at 1 (Math.max(1, min(topK, 50))), so 0 yields a
    // single result, not a 400. (An empty/absent topK also defaults to 10.)
    SearchResponse body = vectorSearch("governance", 0);

    assertThat(body.results()).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  void topKOfOneReturnsAtMostOneResult() {
    SearchResponse body = vectorSearch("governance", 1);

    assertThat(body.results()).hasSize(1);
  }

  private SearchResponse vectorSearch(String query, int topK) {
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/vector", new SearchRequest(query, topK), SearchResponse.class);
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
