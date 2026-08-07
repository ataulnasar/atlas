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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Document filtering across all three search endpoints. Two documents are seeded, both containing
 * the query term and both embedded (via the deterministic {@link FakeEmbeddingService}), so an
 * unfiltered search spans both and a filter must visibly narrow it. Filtering happens in SQL, so
 * these tests also implicitly assert topK is applied after the filter. Tables are truncated before
 * each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SearchFilterIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class SearchFilterIntegrationTest {

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

  private UUID alphaId;
  private UUID betaId;

  @BeforeEach
  void seed() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    alphaId = seedReadyDocument("alpha.pdf");
    seedEmbeddedChunk(alphaId, 0, "alpha governance policy one");
    seedEmbeddedChunk(alphaId, 1, "alpha governance policy two");
    betaId = seedReadyDocument("beta.pdf");
    seedEmbeddedChunk(betaId, 0, "beta governance rule one");
    seedEmbeddedChunk(betaId, 1, "beta governance rule two");
  }

  @Test
  void unfilteredVectorSearchSpansBothDocuments() {
    SearchResponse body = vectorSearch("governance", 10, null);

    assertThat(body.results())
        .extracting(SearchHit::documentFilename)
        .contains("alpha.pdf", "beta.pdf");
  }

  @Test
  void vectorSearchFilteredByDocumentIdReturnsOnlyThatDocument() {
    SearchResponse body = vectorSearch("governance", 10, new SearchFilter(List.of(alphaId), null));

    assertThat(body.results()).isNotEmpty();
    assertThat(body.results()).allSatisfy(hit -> assertThat(hit.documentId()).isEqualTo(alphaId));
  }

  @Test
  void keywordSearchFilteredByFilenameReturnsOnlyThatDocument() {
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/keyword",
            new SearchRequest("governance", 10, new SearchFilter(null, List.of("beta.pdf"))),
            SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isNotEmpty();
    assertThat(response.getBody().results())
        .allSatisfy(hit -> assertThat(hit.documentFilename()).isEqualTo("beta.pdf"));
  }

  @Test
  void hybridSearchAppliesTheFilterToBothLegs() {
    ResponseEntity<HybridSearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/hybrid",
            new SearchRequest("governance", 10, new SearchFilter(List.of(alphaId), null)),
            HybridSearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isNotEmpty();
    // Both the vector and keyword legs were filtered, so nothing from beta can reach fusion.
    assertThat(response.getBody().results())
        .allSatisfy(hit -> assertThat(hit.documentId()).isEqualTo(alphaId));
  }

  @Test
  void filterMatchingDocumentsByEitherIdOrFilenameUnionsThem() {
    SearchResponse body =
        vectorSearch("governance", 10, new SearchFilter(List.of(alphaId), List.of("beta.pdf")));

    // documentIds names alpha, filenames names beta → union covers both.
    assertThat(body.results())
        .extracting(SearchHit::documentFilename)
        .contains("alpha.pdf", "beta.pdf");
  }

  @Test
  void aValidFilterThatMatchesNothingReturnsEmptyResultsNotAnError() {
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/keyword",
            new SearchRequest(
                "governance", 10, new SearchFilter(null, List.of("does-not-exist.pdf"))),
            SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().results()).isEmpty();
  }

  @Test
  void anUnknownFilterFieldIsRejectedWith400() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String rawBody = "{\"query\":\"governance\",\"filter\":{\"documentTypes\":[\"pdf\"]}}";

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity(
            "/api/search/vector", new HttpEntity<>(rawBody, headers), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("invalid_request");
  }

  private SearchResponse vectorSearch(String query, int topK, SearchFilter filter) {
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity(
            "/api/search/vector", new SearchRequest(query, topK, filter), SearchResponse.class);
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
    String literal = VectorLiteral.format(FakeEmbeddingService.vectorFor(content));
    jdbcTemplate.update(
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count, "
            + "embedding) VALUES (?, ?, ?, 1, 1, 10, CAST(? AS vector))",
        documentId,
        chunkIndex,
        content,
        literal);
  }

  @TestConfiguration
  static class FakeEmbeddingConfig {
    @Bean
    FakeEmbeddingService embeddingService() {
      return new FakeEmbeddingService();
    }
  }
}
