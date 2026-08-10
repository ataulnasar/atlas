package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Collections;
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
 * The {@code GET /api/admin/stats} diagnostics endpoint. Tables are truncated before each test and
 * rows seeded with known statuses and embedding states, so the returned counts are deterministic.
 * Runs keyless (no {@code ATLAS_API_KEY}), like the other document integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminStatsIntegrationTest {

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
  void reportsZeroesForAnEmptyCorpus() {
    AdminStatsResponse stats = stats();

    assertThat(stats.totalDocuments()).isZero();
    assertThat(stats.readyDocuments()).isZero();
    assertThat(stats.totalChunks()).isZero();
    assertThat(stats.chunksWithoutEmbedding()).isZero();
  }

  @Test
  void countsDocumentsChunksAndUnembeddedChunks() {
    UUID ready = seedDocument("ready.txt", "READY");
    seedDocument("pending.txt", "PENDING");

    UUID embedded = seedChunk(ready, 0, "This chunk has been embedded.");
    seedChunk(ready, 1, "This chunk is still awaiting an embedding.");
    seedChunk(seedDocument("second-ready.txt", "READY"), 0, "Another unembedded chunk.");
    setEmbedding(embedded);

    AdminStatsResponse stats = stats();

    assertThat(stats.totalDocuments()).isEqualTo(3);
    assertThat(stats.readyDocuments()).isEqualTo(2);
    assertThat(stats.totalChunks()).isEqualTo(3);
    // Only the one chunk we set an embedding on is excluded.
    assertThat(stats.chunksWithoutEmbedding()).isEqualTo(2);
  }

  private AdminStatsResponse stats() {
    ResponseEntity<AdminStatsResponse> response =
        restTemplate.getForEntity("/api/admin/stats", AdminStatsResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private UUID seedDocument(String filename, String status) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO document (filename, content_hash, status) VALUES (?, ?, ?) RETURNING id",
        UUID.class,
        filename,
        "hash-" + UUID.randomUUID(),
        status);
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

  /** Writes a valid 1536-dim zero vector so the chunk stops counting as unembedded. */
  private void setEmbedding(UUID chunkId) {
    String vector = "[" + String.join(",", Collections.nCopies(1536, "0")) + "]";
    jdbcTemplate.update("UPDATE chunk SET embedding = ?::vector WHERE id = ?", vector, chunkId);
  }
}
