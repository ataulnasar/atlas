package com.atlas.core.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Answer-generation tuning for the RAG chat path.
 *
 * <p>{@code model} is the OpenAI chat model id. The default is {@code gpt-5-mini} — the current
 * balanced cheap/good tier and a value Spring AI 1.1.8 recognises (it appears in {@code
 * OpenAiApi.ChatModel} as {@code GPT_5_MINI}); {@code gpt-5-nano} is cheaper/smaller and {@code
 * gpt-5} is the flagship if a deployment wants to trade cost for quality.
 *
 * <p>{@code temperature} is deliberately low (0.1): grounded, cite-only answering wants
 * determinism, not creativity. OpenAI accepts 0–2.
 *
 * <p>{@code contextBudget} caps how many source tokens context assembly may spend (see {@code
 * ContextAssembler}); lower-ranked chunks that don't fit are dropped whole. {@code historyTurns} is
 * how many prior Q&amp;A pairs of the conversation are replayed verbatim into the prompt.
 */
@ConfigurationProperties(prefix = "atlas.generation")
public record GenerationProperties(
    @DefaultValue("gpt-5-mini") String model,
    @DefaultValue("0.1") double temperature,
    @DefaultValue("6000") int contextBudget,
    @DefaultValue("3") int historyTurns) {

  private static final double MAX_TEMPERATURE = 2.0;

  public GenerationProperties {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("atlas.generation.model must not be blank");
    }
    if (temperature < 0 || temperature > MAX_TEMPERATURE) {
      throw new IllegalArgumentException(
          "atlas.generation.temperature must be between 0 and " + MAX_TEMPERATURE);
    }
    if (contextBudget < 1) {
      throw new IllegalArgumentException("atlas.generation.context-budget must be at least 1");
    }
    if (historyTurns < 0) {
      throw new IllegalArgumentException("atlas.generation.history-turns must not be negative");
    }
  }
}
