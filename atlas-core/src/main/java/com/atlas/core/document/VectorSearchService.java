package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.VectorSearchRow;
import com.atlas.core.embedding.EmbeddingService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a vector similarity search: embed the query, rank chunks by cosine similarity in pgvector,
 * and shape each row into a citation-carrying hit (rounded score, leading-content snippet). The
 * {@link EmbeddingService} is passed in rather than injected, because it only exists when a
 * provider is configured — the controller handles its absence.
 */
@Component
class VectorSearchService {

  private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
  private static final int SNIPPET_MAX_CHARS = 300;
  private static final double SCORE_ROUNDING = 10_000d; // 4 decimal places

  private final ChunkRepository chunkRepository;

  VectorSearchService(ChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  List<VectorSearchHit> search(String query, int topK, EmbeddingService embeddingService) {
    long startNanos = System.nanoTime();

    float[] queryVector = embeddingService.embed(List.of(query)).get(0);
    List<VectorSearchRow> rows = chunkRepository.searchByVector(queryVector, topK);
    List<VectorSearchHit> hits = rows.stream().map(VectorSearchService::toHit).toList();

    long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
    // Log the query text, topK and timing — never the embedding vectors.
    log.info(
        "Vector search query=\"{}\" topK={} hits={} took {} ms",
        query,
        topK,
        hits.size(),
        tookMillis);
    return hits;
  }

  private static VectorSearchHit toHit(VectorSearchRow row) {
    String content = row.content();
    String snippet =
        content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS);
    double score = Math.round(row.similarity() * SCORE_ROUNDING) / SCORE_ROUNDING;
    return new VectorSearchHit(
        row.chunkId(),
        row.documentId(),
        row.documentFilename(),
        row.chunkIndex(),
        row.startPage(),
        row.endPage(),
        score,
        snippet);
  }
}
