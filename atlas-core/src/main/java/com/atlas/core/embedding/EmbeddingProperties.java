package com.atlas.core.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding job tuning.
 *
 * <p>{@code batchSize} is how many texts are sent to the provider per call — OpenAI accepts up to
 * 2048 inputs per embeddings request, but the default is deliberately conservative to keep
 * per-request latency and failure blast-radius small.
 *
 * <p>{@code rateLimitBackoff} and {@code maxAttemptsPerBatch} govern how the embedding job reacts
 * to a provider rate limit (HTTP 429): a rate-limited batch is retried up to {@code
 * maxAttemptsPerBatch} times, waiting the interval the provider suggests when it gives one, else
 * {@code rateLimitBackoff}.
 */
@ConfigurationProperties(prefix = "atlas.embedding")
public record EmbeddingProperties(
    @DefaultValue("100") int batchSize,
    @DefaultValue("5s") Duration rateLimitBackoff,
    @DefaultValue("3") int maxAttemptsPerBatch) {

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
    if (rateLimitBackoff == null || rateLimitBackoff.isNegative()) {
      throw new IllegalArgumentException("atlas.embedding.rate-limit-backoff must not be negative");
    }
    if (maxAttemptsPerBatch < 1) {
      throw new IllegalArgumentException(
          "atlas.embedding.max-attempts-per-batch must be at least 1");
    }
  }
}
