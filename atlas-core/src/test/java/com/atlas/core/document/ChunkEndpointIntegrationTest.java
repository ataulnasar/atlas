package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
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
 * The GET /api/chunks/{id} drill-down: returns a chunk's full content and citation metadata (404
 * for an unknown id). No embedding provider needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChunkEndpointIntegrationTest {

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
  void returnsFullContentAndCitationMetadataForAKnownChunk() {
    UUID documentId =
        jdbcTemplate.queryForObject(
            "INSERT INTO document (filename, content_hash, status) "
                + "VALUES ('gdpr.pdf', ?, 'READY') RETURNING id",
            UUID.class,
            "hash-" + UUID.randomUUID());
    String content = "The data protection officer shall have the following tasks: ...";
    UUID chunkId =
        jdbcTemplate.queryForObject(
            "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count)"
                + " VALUES (?, 7, ?, 55, 56, 42) RETURNING id",
            UUID.class,
            documentId,
            content);

    ResponseEntity<ChunkView> response =
        restTemplate.getForEntity("/api/chunks/" + chunkId, ChunkView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ChunkView chunk = response.getBody();
    assertThat(chunk).isNotNull();
    assertThat(chunk.chunkId()).isEqualTo(chunkId);
    assertThat(chunk.documentId()).isEqualTo(documentId);
    assertThat(chunk.documentFilename()).isEqualTo("gdpr.pdf");
    assertThat(chunk.documentTitle()).isEqualTo("gdpr.pdf");
    assertThat(chunk.chunkIndex()).isEqualTo(7);
    assertThat(chunk.startPage()).isEqualTo(55);
    assertThat(chunk.endPage()).isEqualTo(56);
    // Full content, not a truncated snippet.
    assertThat(chunk.content()).isEqualTo(content);
  }

  @Test
  void returns404ForAnUnknownChunkId() {
    ResponseEntity<ApiError> response =
        restTemplate.getForEntity("/api/chunks/" + UUID.randomUUID(), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("chunk_not_found");
  }
}
