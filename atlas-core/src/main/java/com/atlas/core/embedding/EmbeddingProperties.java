package com.atlas.core.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many texts {@link SpringAiEmbeddingService} sends to the provider per call. OpenAI accepts up
 * to 2048 inputs per embeddings request; the default here is deliberately conservative rather than
 * maxing that out, to keep individual request latency and failure blast-radius small.
 */
@ConfigurationProperties(prefix = "atlas.embedding")
public record EmbeddingProperties(int batchSize) {

  private static final int OPENAI_MAX_INPUTS_PER_CALL = 2048;

  public EmbeddingProperties {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("atlas.embedding.batch-size must be positive");
    }
    if (batchSize > OPENAI_MAX_INPUTS_PER_CALL) {
      throw new IllegalArgumentException(
          "atlas.embedding.batch-size must not exceed OpenAI's "
              + OPENAI_MAX_INPUTS_PER_CALL
              + "-input limit per call");
    }
  }
}
