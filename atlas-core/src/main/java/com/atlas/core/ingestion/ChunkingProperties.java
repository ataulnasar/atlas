package com.atlas.core.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token budget for {@link ChunkingService}, measured with the cl100k_base encoding.
 *
 * <p><b>Changing these values is not free:</b> it changes chunk boundaries for every future
 * ingestion, which makes retrieval results (and therefore eval scores) incomparable to runs
 * produced under the old configuration. Treat a chunking-config change like a schema change for
 * eval purposes — the corpus must be re-ingested, and prior {@code atlas-eval compare} baselines
 * retired, before comparing again.
 */
@ConfigurationProperties(prefix = "atlas.chunking")
public record ChunkingProperties(int maxTokens, int overlapTokens) {

  public ChunkingProperties {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("atlas.chunking.max-tokens must be positive");
    }
    if (overlapTokens < 0) {
      throw new IllegalArgumentException("atlas.chunking.overlap-tokens must not be negative");
    }
    if (overlapTokens >= maxTokens) {
      throw new IllegalArgumentException(
          "atlas.chunking.overlap-tokens must be less than atlas.chunking.max-tokens");
    }
  }
}
