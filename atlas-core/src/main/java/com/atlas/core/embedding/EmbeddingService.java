package com.atlas.core.embedding;

import java.util.List;

/**
 * Atlas's own thin interface over an embedding provider. Per ADR 0002, application code depends on
 * this — never directly on Spring AI's {@code EmbeddingModel} — so a provider or Spring AI API
 * change is absorbed in one place ({@link SpringAiEmbeddingService}).
 */
public interface EmbeddingService {

  /**
   * Embeds each input text, preserving order: result element {@code i} corresponds to {@code
   * texts.get(i)}. Throws {@link EmbeddingException} if the underlying provider call fails.
   */
  List<float[]> embed(List<String> texts);
}
