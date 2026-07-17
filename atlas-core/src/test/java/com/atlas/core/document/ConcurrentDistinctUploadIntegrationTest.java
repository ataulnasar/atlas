package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Fires many uploads of distinct documents at once over real HTTP and confirms the async ingestion
 * pipeline keeps each document's chunks isolated — no chunk from document A should ever land under
 * document B, even when their processing overlaps on the shared ingestion executor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConcurrentDistinctUploadIntegrationTest {

  private static final int DOCUMENT_COUNT = 10;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerStorageProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentUploadsOfDifferentDocumentsDoNotCrossContaminateChunks() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(DOCUMENT_COUNT);
    List<Callable<UUID>> uploads =
        IntStream.range(0, DOCUMENT_COUNT)
            .<Callable<UUID>>mapToObj(i -> () -> upload(documentMarker(i), documentContent(i)))
            .toList();
    List<UUID> documentIds;
    try {
      List<Future<UUID>> futures = executor.invokeAll(uploads);
      documentIds = new ArrayList<>();
      for (Future<UUID> future : futures) {
        documentIds.add(future.get());
      }
    } finally {
      executor.shutdown();
    }

    assertThat(documentIds).doesNotHaveDuplicates().hasSize(DOCUMENT_COUNT);
    for (UUID documentId : documentIds) {
      awaitStatus(documentId, DocumentStatus.READY);
    }

    for (int i = 0; i < DOCUMENT_COUNT; i++) {
      UUID documentId = documentIds.get(i);
      String ownMarker = documentMarker(i);

      List<String> chunkContents =
          jdbcTemplate.queryForList(
              "SELECT content FROM chunk WHERE document_id = ? ORDER BY chunk_index",
              String.class,
              documentId);
      assertThat(chunkContents).isNotEmpty();

      for (String chunkContent : chunkContents) {
        assertThat(chunkContent).contains(ownMarker);
        for (int j = 0; j < DOCUMENT_COUNT; j++) {
          if (j != i) {
            assertThat(chunkContent).doesNotContain(documentMarker(j));
          }
        }
      }
    }
  }

  private String documentMarker(int index) {
    return "UNIQUE-DOC-MARKER-" + index;
  }

  private String documentContent(int index) {
    String marker = documentMarker(index);
    StringBuilder builder = new StringBuilder();
    for (int p = 0; p < 3; p++) {
      builder
          .append(marker)
          .append(" paragraph ")
          .append(p)
          .append(" carries some filler content specific to this document so each chunk can be")
          .append(" attributed back to its source upload without ambiguity.\n\n");
    }
    return builder.toString();
  }

  private UUID upload(String marker, String content) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return marker + ".txt";
          }
        };
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.TEXT_PLAIN);

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(fileResource, fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    ResponseEntity<DocumentUploadResponse> response =
        restTemplate.postForEntity(
            "/api/documents", new HttpEntity<>(body, requestHeaders), DocumentUploadResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
  }

  private void awaitStatus(UUID documentId, DocumentStatus expected) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                restTemplate.getForObject(
                    "/api/documents/" + documentId, DocumentStatusResponse.class),
            response -> response.status() == expected);
  }
}
