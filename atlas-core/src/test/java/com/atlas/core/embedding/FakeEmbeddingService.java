package com.atlas.core.embedding;

import java.util.List;
import java.util.Random;

/**
 * Deterministic in-memory {@link EmbeddingService} for tests — no API key, no network. Produces a
 * distinct, reproducible 1536-dimension vector per input text (seeded from the text's hash), so
 * different chunks get different vectors just like a real provider. Its {@link
 * #setFailing(boolean)} toggle lets a test drive the embedding-failure path.
 */
public class FakeEmbeddingService implements EmbeddingService {

  public static final int DIMENSIONS = 1536;

  private volatile boolean failing;

  public void setFailing(boolean failing) {
    this.failing = failing;
  }

  @Override
  public List<float[]> embed(List<String> texts) {
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
