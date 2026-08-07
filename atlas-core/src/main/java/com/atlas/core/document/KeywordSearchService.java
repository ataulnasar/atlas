package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.RankedChunkRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a keyword (full-text) search over chunks using PostgreSQL's {@code content_tsv} column, and
 * shapes each row into a citation-carrying hit via the shared {@link SearchResultMapper}. Needs no
 * embedding provider, so it's the search that keeps working in keyless mode.
 */
@Component
class KeywordSearchService {

  private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

  private final ChunkRepository chunkRepository;

  KeywordSearchService(ChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  List<SearchHit> search(String query, int topK, SearchFilter filter) {
    long startNanos = System.nanoTime();

    List<RankedChunkRow> rows = chunkRepository.searchByKeyword(query, topK, filter);
    List<SearchHit> hits = SearchResultMapper.toHits(rows);

    long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
    log.info(
        "Keyword search query=\"{}\" topK={} filtered={} hits={} took {} ms",
        query,
        topK,
        !filter.isEmpty(),
        hits.size(),
        tookMillis);
    return hits;
  }
}
