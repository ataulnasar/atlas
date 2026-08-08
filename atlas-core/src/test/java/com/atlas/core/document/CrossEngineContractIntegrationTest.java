package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.embedding.FakeEmbeddingService;
import com.atlas.core.embedding.VectorLiteral;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The contract every search endpoint shares — same citation field set on every hit, topK respected,
 * document filter honored — asserted once, parameterized over all three endpoints against one
 * seeded corpus, rather than duplicated per engine. Guards against the endpoints drifting apart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CrossEngineContractIntegrationTest.FakeEmbeddingConfig.class)
@Testcontainers
class CrossEngineContractIntegrationTest {

  // The citation fields every hit must carry, whatever the engine (hybrid adds foundBy/ranks on
  // top; SearchHit and HybridSearchHit both @JsonUnwrap the same Citation).
  private static final List<String> CITATION_FIELDS =
      List.of(
          "citationId",
          "chunkId",
          "documentId",
          "documentFilename",
          "documentTitle",
          "startPage",
          "endPage",
          "snippet",
          "chunkIndex",
          "score");

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

  private final ObjectMapper objectMapper = new ObjectMapper();

  private UUID alphaId;

  @BeforeEach
  void seed() {
    jdbcTemplate.execute("TRUNCATE document CASCADE");
    alphaId = seedReadyDocument("alpha.pdf");
    for (int i = 0; i < 4; i++) {
      seedEmbeddedChunk(alphaId, i, "alpha compliance obligation " + i);
    }
    UUID betaId = seedReadyDocument("beta.pdf");
    for (int i = 0; i < 4; i++) {
      seedEmbeddedChunk(betaId, i, "beta compliance obligation " + i);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"vector", "keyword", "hybrid"})
  void everyHitCarriesTheFullCitationFieldSet(String engine) throws Exception {
    JsonNode results = search(engine, new SearchRequest("compliance", 10, null));

    assertThat(results).isNotEmpty();
    for (JsonNode hit : results) {
      assertThat(fieldNames(hit)).containsAll(CITATION_FIELDS);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"vector", "keyword", "hybrid"})
  void topKIsRespected(String engine) throws Exception {
    JsonNode results = search(engine, new SearchRequest("compliance", 3, null));

    assertThat(results.size()).isLessThanOrEqualTo(3);
    assertThat(results).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"vector", "keyword", "hybrid"})
  void documentFilterIsHonored(String engine) throws Exception {
    JsonNode results =
        search(
            engine, new SearchRequest("compliance", 10, new SearchFilter(List.of(alphaId), null)));

    assertThat(results).isNotEmpty();
    for (JsonNode hit : results) {
      assertThat(hit.get("documentId").asText()).isEqualTo(alphaId.toString());
    }
  }

  private JsonNode search(String engine, SearchRequest request) throws Exception {
    ResponseEntity<String> response =
        restTemplate.postForEntity("/api/search/" + engine, request, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return objectMapper.readTree(response.getBody()).get("results");
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
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
