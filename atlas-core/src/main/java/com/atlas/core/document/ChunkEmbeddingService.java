package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.ChunkToEmbed;
import com.atlas.core.embedding.EmbeddingProperties;
import com.atlas.core.embedding.EmbeddingService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embeds chunk content and persists the vectors, batch by batch. Shared by two callers: the
 * ingestion path (one freshly-chunked document) and the admin backfill (every not-yet-embedded
 * chunk of every READY document). Each batch is embedded then written in its own repository
 * transaction, so a failure or interruption leaves already-embedded batches committed rather than
 * rolling back progress — which is also what makes the backfill idempotent and re-runnable.
 */
@Component
class ChunkEmbeddingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingService.class);

  private final ChunkRepository chunkRepository;
  private final int batchSize;

  ChunkEmbeddingService(ChunkRepository chunkRepository, EmbeddingProperties embeddingProperties) {
    this.chunkRepository = chunkRepository;
    this.batchSize = embeddingProperties.batchSize();
  }

  /**
   * Embeds a single document's not-yet-embedded chunks. Propagates the {@link EmbeddingService}'s
   * exception on provider failure so the ingestion path can route the document to FAILED; any
   * batches already written stay put (the retry path deletes and re-inserts the document's chunks,
   * so a partial write can't leave stale vectors behind).
   */
  int embedDocumentChunks(UUID documentId, EmbeddingService embeddingService) {
    List<ChunkToEmbed> chunks = chunkRepository.findChunksWithoutEmbedding(documentId);
    int embedded = 0;
    for (int start = 0; start < chunks.size(); start += batchSize) {
      int end = Math.min(start + batchSize, chunks.size());
      embedAndPersistBatch(chunks.subList(start, end), embeddingService);
      embedded += end - start;
    }
    return embedded;
  }

  /**
   * Embeds every not-yet-embedded chunk of every READY document, committing each batch as it goes.
   * Idempotent by construction: a chunk is re-fetched only while its embedding is still NULL, so a
   * re-run after an interruption resumes from exactly where it stopped.
   */
  BackfillResult backfill(EmbeddingService embeddingService) {
    int totalChunks = 0;
    Set<UUID> documentsTouched = new HashSet<>();
    while (true) {
      List<ChunkToEmbed> batch =
          chunkRepository.findNextChunksWithoutEmbeddingForReadyDocuments(batchSize);
      if (batch.isEmpty()) {
        break;
      }
      embedAndPersistBatch(batch, embeddingService);
      totalChunks += batch.size();
      batch.forEach(chunk -> documentsTouched.add(chunk.documentId()));
      log.info(
          "Embedding backfill progress: {} chunk(s) embedded across {} document(s) so far",
          totalChunks,
          documentsTouched.size());
    }
    return new BackfillResult(totalChunks, documentsTouched.size());
  }

  private void embedAndPersistBatch(List<ChunkToEmbed> batch, EmbeddingService embeddingService) {
    List<String> contents = batch.stream().map(ChunkToEmbed::content).toList();
    List<float[]> vectors = embeddingService.embed(contents);
    List<UUID> ids = batch.stream().map(ChunkToEmbed::id).toList();
    chunkRepository.updateEmbeddings(ids, vectors);
  }

  record BackfillResult(int chunksEmbedded, int documentsTouched) {}
}
