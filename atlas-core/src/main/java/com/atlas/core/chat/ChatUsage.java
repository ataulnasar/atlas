package com.atlas.core.chat;

/**
 * Token/cost usage metadata for a chat response. A placeholder for now — every field is nullable
 * and left null until generation is implemented (the RAG/LLM cards), at which point the model name
 * and token counts get populated. Kept as a distinct nested object so populating it later doesn't
 * change the top-level {@link ChatResponse} shape. In the SSE streaming variant this is what the
 * terminal {@code done} event carries.
 */
public record ChatUsage(
    Integer promptTokens, Integer completionTokens, Integer totalTokens, String model) {

  /** The not-yet-populated usage carried by responses until generation lands. */
  public static ChatUsage placeholder() {
    return new ChatUsage(null, null, null, null);
  }
}
