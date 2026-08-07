package com.atlas.core.embedding;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Wraps a Spring AI {@link EmbeddingModel}, splitting the input into batches of at most {@code
 * batchSize} texts per provider call. A plain class rather than a Spring {@code @Service} — see
 * {@link EmbeddingConfig} for why — which also makes it trivial to unit test against a mocked
 * {@link EmbeddingModel} with no Spring context involved.
 */
class SpringAiEmbeddingService implements EmbeddingService {

  private final EmbeddingModel embeddingModel;
  private final int batchSize;

  SpringAiEmbeddingService(EmbeddingModel embeddingModel, int batchSize) {
    this.embeddingModel = embeddingModel;
    this.batchSize = batchSize;
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }

    List<float[]> results = new ArrayList<>(texts.size());
    for (int start = 0; start < texts.size(); start += batchSize) {
      int end = Math.min(start + batchSize, texts.size());
      List<String> batch = texts.subList(start, end);
      try {
        results.addAll(embeddingModel.embed(batch));
      } catch (Exception e) {
        throw new EmbeddingException(
            "Failed to embed batch [" + start + ", " + end + ") of " + texts.size() + " text(s)",
            e);
      }
    }
    return results;
  }
}
