package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * Keyword full-text search over the generated {@code content_tsv} column. No embedding bean is
 * registered — keyword search must work without a provider (that's the point of it), which this
 * whole class exercises. Chunks are seeded directly with known terms and the tables truncated
 * before each test, so ranking and counts are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeywordSearchIntegrationTest {

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
  void termDenseChunkOutranksASingleMention() {
    UUID documentId = seedReadyDocument("policy.txt");
    UUID denseChunk =
        seedChunk(
            documentId,
            0,
            "Compliance compliance compliance: this compliance section is all about compliance"
                + " obligations and compliance controls.");
    UUID sparseChunk =
        seedChunk(
            documentId,
            1,
            "This paragraph is mostly about data retention schedules and mentions compliance"
                + " only once in passing.");

    List<SearchHit> results = search("compliance", 10);

    assertThat(results).extracting(SearchHit::chunkId).containsExactly(denseChunk, sparseChunk);
    assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
  }

  @Test
  void topKLimitsTheNumberOfResults() {
    UUID documentId = seedReadyDocument("many.txt");
    for (int i = 0; i < 5; i++) {
      seedChunk(documentId, i, "Chunk " + i + " discusses governance and governance policy.");
    }

    assertThat(search("governance", 2)).hasSize(2);
  }

  @Test
  void topKIsCappedAtFifty() {
    UUID documentId = seedReadyDocument("big.txt");
    for (int i = 0; i < 51; i++) {
      seedChunk(documentId, i, "Row " + i + " mentions oversight and oversight duties.");
    }

    assertThat(search("oversight", 1000)).hasSize(50);
  }

  @Test
  void noMatchesReturnsEmptyResultsNotAnError() {
    UUID documentId = seedReadyDocument("present.txt");
    seedChunk(documentId, 0, "This chunk is entirely about transparency reporting.");

    ResponseEntity<SearchResponse> response = searchResponse("nonexistentterm", 10);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isEmpty();
  }

  @Test
  void aNaturalLanguageQueryWithStopwordsStillMatchesContentWords() {
    UUID documentId = seedReadyDocument("nl.txt");
    UUID chunk =
        seedChunk(documentId, 0, "The accessibility requirements apply to public sector websites.");

    // Stopwords ("what are the ... for") drop out; "accessibility" / "requirements" still match.
    List<SearchHit> results = search("what are the accessibility requirements for websites?", 10);

    assertThat(results).extracting(SearchHit::chunkId).contains(chunk);
  }

  @Test
  void anAllStopwordQueryReducesToEmptyAndReturnsNoResults() {
    UUID documentId = seedReadyDocument("stop.txt");
    seedChunk(documentId, 0, "Some genuine content about encryption standards.");

    ResponseEntity<SearchResponse> response = searchResponse("what is the of an and to", 10);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isEmpty();
  }

  @Test
  void hitsCarryCitationMetadataAndASnippet() {
    UUID documentId = seedReadyDocument("cited.txt");
    UUID chunk = seedChunk(documentId, 3, "A detailed discussion of interoperability mandates.");

    SearchHit hit = search("interoperability", 10).get(0);

    assertThat(hit.chunkId()).isEqualTo(chunk);
    assertThat(hit.documentId()).isEqualTo(documentId);
    assertThat(hit.documentFilename()).isEqualTo("cited.txt");
    assertThat(hit.chunkIndex()).isEqualTo(3);
    assertThat(hit.startPage()).isEqualTo(1);
    assertThat(hit.endPage()).isEqualTo(1);
    assertThat(hit.score()).isGreaterThan(0.0);
    assertThat(hit.snippet()).contains("interoperability");
  }

  @Test
  void aBlankQueryIsRejected() {
    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/search/keyword", new SearchRequest("   ", 10), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("invalid_query");
  }

  private List<SearchHit> search(String query, int topK) {
    ResponseEntity<SearchResponse> response = searchResponse(query, topK);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().results();
  }

  private ResponseEntity<SearchResponse> searchResponse(String query, int topK) {
    return restTemplate.postForEntity(
        "/api/search/keyword", new SearchRequest(query, topK), SearchResponse.class);
  }

  private UUID seedReadyDocument(String filename) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (?, ?, 'READY') RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID());
  }

  private UUID seedChunk(UUID documentId, int chunkIndex, String content) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count) "
            + "VALUES (?, ?, ?, 1, 1, 10) RETURNING id",
        UUID.class,
        documentId,
        chunkIndex,
        content);
  }
}
