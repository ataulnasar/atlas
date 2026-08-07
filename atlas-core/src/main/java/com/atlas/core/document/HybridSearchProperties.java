package com.atlas.core.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hybrid search tuning.
 *
 * <p>{@code candidatePool} is how many results each underlying search (vector and keyword) fetches
 * before fusion — deliberately larger than the caller's topK so Reciprocal Rank Fusion has material
 * to work with (a chunk that's, say, 30th in one list but 5th in the other can still surface).
 *
 * <p>{@code rrfK} is the RRF constant: a fused chunk scores {@code sum over lists of 1 / (rrfK +
 * rank)}. 60 is the canonical value from the original RRF paper; larger flattens the contribution
 * of top ranks, smaller sharpens it.
 */
@ConfigurationProperties(prefix = "atlas.search.hybrid")
public record HybridSearchProperties(
    @DefaultValue("50") int candidatePool, @DefaultValue("60") int rrfK) {

  public HybridSearchProperties {
    if (candidatePool < 1) {
      throw new IllegalArgumentException("atlas.search.hybrid.candidate-pool must be at least 1");
    }
    if (rrfK < 1) {
      throw new IllegalArgumentException("atlas.search.hybrid.rrf-k must be at least 1");
    }
  }
}
