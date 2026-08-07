package com.atlas.core.embedding;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory {@link EmbeddingService} for tests — no API key, no network. Produces a
 * distinct, reproducible 1536-dimension vector per input text (seeded from the text's hash), so
 * different chunks get different vectors just like a real provider. Its {@link
 * #setFailing(boolean)} toggle drives the hard-failure path, and {@link
 * #failWithRateLimitTimes(int)} drives the transient rate-limit (HTTP 429) path.
 */
public class FakeEmbeddingService implements EmbeddingService {

  public static final int DIMENSIONS = 1536;

  private volatile boolean failing;
  private final AtomicInteger rateLimitFailuresRemaining = new AtomicInteger(0);

  public void setFailing(boolean failing) {
    this.failing = failing;
  }

  /**
   * The next {@code times} {@link #embed} calls throw a rate-limit-shaped exception, then succeed.
   */
  public void failWithRateLimitTimes(int times) {
    rateLimitFailuresRemaining.set(times);
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    if (rateLimitFailuresRemaining.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0)
        > 0) {
      // Shaped like a wrapped OpenAI 429 as it reaches ChunkEmbeddingService (SpringAiEmbedding
      // Service wraps the provider exception in an EmbeddingException).
      throw new EmbeddingException(
          "Failed to embed batch",
          new RuntimeException(
              "429 Too Many Requests: Rate limit reached on tokens per min (TPM)."));
    }
    if (failing) {
      throw new EmbeddingException(
          "simulated embedding failure", new RuntimeException("fake provider is down"));
    }
    return texts.stream().map(FakeEmbeddingService::deterministicVector).toList();
  }

  private static float[] deterministicVector(String text) {
    Random random = new Random(text.hashCode());
    float[] vector = new float[DIMENSIONS];
    for (int i = 0; i < DIMENSIONS; i++) {
      vector[i] = random.nextFloat();
    }
    return vector;
  }
}
