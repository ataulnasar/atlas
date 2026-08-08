package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The degraded-state contract when a document is only partially embedded (e.g. mid-backfill): some
 * chunks have a NULL embedding. The engines diverge exactly as their SQL dictates:
 *
 * <ul>
 *   <li><b>vector</b> excludes NULL-embedding chunks — they aren't in the HNSW-searchable set.
 *   <li><b>keyword</b> returns them — {@code content_tsv} is generated from content regardless of
 *       embedding, so they're fully full-text searchable.
 *   <li><b>hybrid</b> surfaces a NULL-embedding chunk <b>only via its keyword leg</b>, marked
 *       {@code foundBy="keyword"} (the vector leg still excludes it). This is a deliberate nuance
 *       over the loose "hybrid returns only embedded chunks" framing: fusion is a union, so a chunk
 *       that keyword can find is legitimately returned — it's real content in a READY document —
 *       and {@code foundBy} makes its provenance explicit. Not a bug; correct union semantics.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PartialEmbeddingSearchIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class PartialEmbeddingSearchIntegrationTest {

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

  private final List<UUID> embeddedIds = new ArrayList<>();
  private final List<UUID> nullIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    embeddedIds.clear();
    nullIds.clear();
    UUID documentId = seedReadyDocument("partial.pdf");
    int index = 0;
    for (int i = 0; i < 3; i++) {
      embeddedIds.add(seedChunk(documentId, index++, "governance obligation embedded " + i, true));
    }
    for (int i = 0; i < 2; i++) {
      nullIds.add(seedChunk(documentId, index++, "governance obligation unembedded " + i, false));
    }
  }

  @Test
  void vectorSearchExcludesNullEmbeddingChunks() {
    List<UUID> returned =
        restTemplate
            .postForEntity(
                "/api/search/vector", new SearchRequest("governance", 20), SearchResponse.class)
            .getBody()
            .results()
            .stream()
            .map(SearchHit::chunkId)
            .toList();

    assertThat(returned)
        .isNotEmpty()
        .containsAnyElementsOf(embeddedIds)
        .doesNotContainAnyElementsOf(nullIds);
  }

  @Test
  void keywordSearchReturnsEveryMatchingChunkEmbeddedOrNot() {
    List<UUID> returned =
        restTemplate
            .postForEntity(
                "/api/search/keyword", new SearchRequest("governance", 20), SearchResponse.class)
            .getBody()
            .results()
            .stream()
            .map(SearchHit::chunkId)
            .toList();

    assertThat(returned).containsAll(embeddedIds).containsAll(nullIds);
  }

  @Test
  void hybridSurfacesNullEmbeddingChunksOnlyViaTheKeywordLeg() {
    List<HybridSearchHit> results =
        restTemplate
            .postForEntity(
                "/api/search/hybrid",
                new SearchRequest("governance", 20),
                HybridSearchResponse.class)
            .getBody()
            .results();

    List<UUID> returned = results.stream().map(HybridSearchHit::chunkId).toList();
    // Un-embedded chunks are still reachable through keyword...
    assertThat(returned).containsAll(nullIds);
    // ...but never via the vector leg: any un-embedded hit is foundBy exactly "keyword".
    assertThat(results)
        .filteredOn(hit -> nullIds.contains(hit.chunkId()))
        .allSatisfy(
            hit -> {
              assertThat(hit.foundBy()).isEqualTo("keyword");
              assertThat(hit.vectorRank()).isNull();
            });
  }

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  private UUID seedChunk(UUID documentId, int chunkIndex, String content, boolean embedded) {
    if (embedded) {
      return jdbcTemplate.queryForObject(
          "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
              + "embedding) VALUES (?, ?, ?, 1, 1, 10, CAST(? AS vector)) RETURNING id",
          UUID.class,
          documentId,
          chunkIndex,
          content,
          VectorLiteral.format(FakeEmbeddingService.vectorFor(content)));
    }
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
