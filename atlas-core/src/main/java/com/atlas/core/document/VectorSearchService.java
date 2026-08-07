package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.RankedChunkRow;
import com.atlas.core.embedding.EmbeddingService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a vector similarity search: embed the query, rank chunks by cosine similarity in pgvector,
 * and shape each row into a citation-carrying hit via the shared {@link SearchResultMapper}. The
 * {@link EmbeddingService} is passed in rather than injected, because it only exists when a
 * provider is configured — the controller handles its absence.
 */
@Component
class VectorSearchService {

  private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

  private final ChunkRepository chunkRepository;

  VectorSearchService(ChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  List<SearchHit> search(String query, int topK, EmbeddingService embeddingService) {
    long startNanos = System.nanoTime();

    float[] queryVector = embeddingService.embed(List.of(query)).get(0);
    List<RankedChunkRow> rows = chunkRepository.searchByVector(queryVector, topK);
    List<SearchHit> hits = SearchResultMapper.toHits(rows);

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
}
