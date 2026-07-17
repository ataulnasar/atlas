package com.atlas.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises {@link IngestionProcessor}'s claim guard directly, bypassing the upload HTTP path and
 * the event-driven trigger, by racing two threads against the same PENDING document — the same
 * scenario a redelivered event or an overlapping retry would create.
 */
@SpringBootTest
@Testcontainers
class IngestionProcessorConcurrencyIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @TempDir static Path STORAGE_ROOT;

  @DynamicPropertySource
  static void registerStorageProperties(DynamicPropertyRegistry registry) {
    registry.add("atlas.storage.path", () -> STORAGE_ROOT.toString());
  }

  @Autowired private DocumentRepository documentRepository;
  @Autowired private ChunkRepository chunkRepository;
  @Autowired private LocalFileStorageService fileStorageService;
  @Autowired private IngestionProcessor ingestionProcessor;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void onlyOneOfTwoConcurrentClaimAttemptsSucceeds() throws Exception {
    UUID documentId = insertPendingDocument("claim-race.txt", "claim guard race content");

    List<Boolean> results = raceTwoThreads(() -> documentRepository.claimForProcessing(documentId));

    assertThat(results).containsExactlyInAnyOrder(true, false);
  }

  @Test
  void concurrentProcessCallsForTheSameDocumentInsertChunksExactlyOnceAndLoserNoOps()
      throws Exception {
    String content =
        "First paragraph with real content to chunk.\n\n"
            + "Second paragraph with more content here to chunk as well.";
    UUID documentId = insertPendingDocument("claim-race-process.txt", content);

    raceTwoThreads(
        () -> {
          ingestionProcessor.process(documentId);
          return true;
        });

    DocumentStatus status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM document WHERE id = ?",
            (rs, rowNum) -> DocumentStatus.valueOf(rs.getString(1)),
            documentId);
    assertThat(status).isEqualTo(DocumentStatus.READY);

    List<Map<String, Object>> chunks =
        jdbcTemplate.queryForList(
            "SELECT chunk_index FROM chunk WHERE document_id = ? ORDER BY chunk_index", documentId);
    assertThat(chunks).isNotEmpty();
    // The (document_id, chunk_index) unique constraint would make a double insert impossible
    // to observe as duplicates here — it would instead have surfaced as an exception in the
    // "losing" thread and flipped the document to FAILED, which the status assertion above rules
    // out. A clean, non-doubled set of chunk rows is the positive evidence that insertAll ran
    // exactly once.
    assertThat(chunkRepository.countByDocumentId(documentId)).isEqualTo(chunks.size());
    List<Integer> indices = chunks.stream().map(c -> (Integer) c.get("chunk_index")).toList();
    assertThat(indices).doesNotHaveDuplicates();
  }

  private <T> List<T> raceTwoThreads(Callable<T> task) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<T> synced =
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return task.call();
          };
      Future<T> first = executor.submit(synced);
      Future<T> second = executor.submit(synced);
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdown();
    }
  }

  private UUID insertPendingDocument(String filename, String content) {
    Document document = documentRepository.insertPending(filename, UUID.randomUUID().toString());
    fileStorageService.store(
        document.id(),
        SupportedContentType.TXT,
        new MockMultipartFile(
            "file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8)));
    return document.id();
  }
}
