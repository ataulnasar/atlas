package com.atlas.core.document;

import com.atlas.core.embedding.EmbeddingService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Fuses vector and keyword search with Reciprocal Rank Fusion. Each source contributes a candidate
 * pool (wider than the caller's topK), and a chunk's fused score is {@code sum over lists of 1 /
 * (rrfK + rank)} — so a chunk that ranks decently in <em>both</em> lists outranks one that tops
 * only a single list, which is the point of hybrid retrieval.
 *
 * <p>Degrades gracefully in keyless mode: with no embedding provider it runs keyword-only (still
 * through the same fusion, so the response shape and ranks stay consistent) rather than failing.
 * This is meant to be the endpoint a UI can always call.
 */
@Component
public class HybridSearchService {

  private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
  private static final double SCORE_ROUNDING = 1_000_000d; // 6 dp — RRF scores are small

  static final String FOUND_BY_VECTOR = "vector";
  static final String FOUND_BY_KEYWORD = "keyword";
  static final String FOUND_BY_BOTH = "both";

  private final VectorSearchService vectorSearchService;
  private final KeywordSearchService keywordSearchService;
  private final Executor executor;
  private final int candidatePool;
  private final int rrfK;
  private final AtomicBoolean keylessDegradeLogged = new AtomicBoolean(false);

  HybridSearchService(
      VectorSearchService vectorSearchService,
      KeywordSearchService keywordSearchService,
      @Qualifier(IngestionExecutorConfig.EXECUTOR_BEAN_NAME) Executor executor,
      HybridSearchProperties properties) {
    this.vectorSearchService = vectorSearchService;
    this.keywordSearchService = keywordSearchService;
    this.executor = executor;
    this.candidatePool = properties.candidatePool();
    this.rrfK = properties.rrfK();
  }

  /**
   * Runs both searches (concurrently when an embedding provider is present) over a candidate pool,
   * fuses them with RRF, and returns the topK fused hits. {@code embeddingService} may be null —
   * keyless mode — in which case only keyword search runs.
   */
  public List<HybridSearchHit> search(
      String query, int topK, SearchFilter filter, EmbeddingService embeddingService) {
    long startNanos = System.nanoTime();
    int poolSize = Math.max(candidatePool, topK);

    List<SearchHit> vectorHits;
    List<SearchHit> keywordHits;
    if (embeddingService == null) {
      logKeylessDegradeOnce();
      vectorHits = List.of();
      keywordHits = keywordSearchService.search(query, poolSize, filter);
    } else {
      // Independent searches — run them concurrently so vector's embedding round-trip and keyword's
      // FTS query overlap instead of summing their latencies. The same filter applies to both legs
      // so fusion only ever sees candidates from the filtered documents.
      CompletableFuture<List<SearchHit>> vectorFuture =
          CompletableFuture.supplyAsync(
              () -> vectorSearchService.search(query, poolSize, filter, embeddingService),
              executor);
      CompletableFuture<List<SearchHit>> keywordFuture =
          CompletableFuture.supplyAsync(
              () -> keywordSearchService.search(query, poolSize, filter), executor);
      vectorHits = join(vectorFuture);
      keywordHits = join(keywordFuture);
    }

    List<HybridSearchHit> fused = fuse(vectorHits, keywordHits, rrfK, topK);

    long tookMillis = (System.nanoTime() - startNanos) / 1_000_000;
    log.info(
        "Hybrid search query=\"{}\" topK={} filtered={} vectorCandidates={} keywordCandidates={} fused={} took {} ms",
        query,
        topK,
        !filter.isEmpty(),
        vectorHits.size(),
        keywordHits.size(),
        fused.size(),
        tookMillis);
    return fused;
  }

  /**
   * Reciprocal Rank Fusion over two ranked lists (each already in rank order, rank being 1-based
   * position). Pure function of its inputs — no Spring, no I/O — so the RRF math is unit-testable
   * in isolation.
   */
  static List<HybridSearchHit> fuse(
      List<SearchHit> vectorHits, List<SearchHit> keywordHits, int rrfK, int topK) {
    Map<UUID, Integer> vectorRanks = ranksByChunkId(vectorHits);
    Map<UUID, Integer> keywordRanks = ranksByChunkId(keywordHits);

    // One entry per distinct chunk, keeping a SearchHit for its citation metadata (identical
    // whichever list it came from). Insertion order is vector-then-keyword for stable tie handling.
    Map<UUID, SearchHit> metadata = new LinkedHashMap<>();
    vectorHits.forEach(hit -> metadata.putIfAbsent(hit.chunkId(), hit));
    keywordHits.forEach(hit -> metadata.putIfAbsent(hit.chunkId(), hit));

    List<FusedCandidate> candidates = new ArrayList<>(metadata.size());
    for (Map.Entry<UUID, SearchHit> entry : metadata.entrySet()) {
      UUID chunkId = entry.getKey();
      Integer vectorRank = vectorRanks.get(chunkId);
      Integer keywordRank = keywordRanks.get(chunkId);
      double score = 0.0;
      if (vectorRank != null) {
        score += 1.0 / (rrfK + vectorRank);
      }
      if (keywordRank != null) {
        score += 1.0 / (rrfK + keywordRank);
      }
      candidates.add(new FusedCandidate(entry.getValue(), score, vectorRank, keywordRank));
    }

    // Sort by raw (unrounded) score so display rounding can't reorder; break ties by chunk id for
    // a deterministic result.
    candidates.sort(
        Comparator.comparingDouble(FusedCandidate::score)
            .reversed()
            .thenComparing(candidate -> candidate.hit().chunkId()));

    List<HybridSearchHit> results = new ArrayList<>(Math.min(topK, candidates.size()));
    for (FusedCandidate candidate : candidates) {
      if (results.size() == topK) {
        break;
      }
      // citationId is assigned by final fused rank (c1, c2, …) — the legs' own citationIds are
      // discarded, since this response's ordering is what a caller cites against.
      results.add(toHybridHit(candidate, results.size() + 1));
    }
    return results;
  }

  private static Map<UUID, Integer> ranksByChunkId(List<SearchHit> hits) {
    Map<UUID, Integer> ranks = new HashMap<>();
    for (int i = 0; i < hits.size(); i++) {
      ranks.put(hits.get(i).chunkId(), i + 1); // rank is 1-based
    }
    return ranks;
  }

  private static HybridSearchHit toHybridHit(FusedCandidate candidate, int rank) {
    SearchHit hit = candidate.hit();
    String foundBy;
    if (candidate.vectorRank() != null && candidate.keywordRank() != null) {
      foundBy = FOUND_BY_BOTH;
    } else if (candidate.vectorRank() != null) {
      foundBy = FOUND_BY_VECTOR;
    } else {
      foundBy = FOUND_BY_KEYWORD;
    }
    double score = Math.round(candidate.score() * SCORE_ROUNDING) / SCORE_ROUNDING;
    Citation source = hit.citation();
    Citation citation =
        new Citation(
            "c" + rank,
            source.chunkId(),
            source.documentId(),
            source.documentFilename(),
            source.documentTitle(),
            source.startPage(),
            source.endPage(),
            source.snippet());
    return new HybridSearchHit(
        citation,
        hit.chunkIndex(),
        score,
        foundBy,
        candidate.vectorRank(),
        candidate.keywordRank());
  }

  private void logKeylessDegradeOnce() {
    if (keylessDegradeLogged.compareAndSet(false, true)) {
      log.debug("hybrid search degraded to keyword-only — no embedding provider configured");
    }
  }

  private static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }

  private record FusedCandidate(
      SearchHit hit, double score, Integer vectorRank, Integer keywordRank) {}
}
