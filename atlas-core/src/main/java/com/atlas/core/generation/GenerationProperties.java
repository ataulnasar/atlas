package com.atlas.core.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 *
 * <p>{@code cost} holds token prices used only to log an approximate per-request USD estimate.
 * These are configuration, not fetched from any provider API — they default to gpt-5-mini's
 * published rates and must be updated by hand if the model or its pricing changes.
 */
@ConfigurationProperties(prefix = "atlas.generation")
public record GenerationProperties(
    @DefaultValue("gpt-5-mini") String model,
    @DefaultValue("0.1") double temperature,
    @DefaultValue("6000") int contextBudget,
    @DefaultValue("3") int historyTurns,
    @DefaultValue Cost cost) {

  private static final double MAX_TEMPERATURE = 2.0;

  // The convenience constructor below means this type has two constructors, so the canonical one
  // must be marked as the binding target for @ConfigurationProperties.
  @ConstructorBinding
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

  /** Convenience for callers/tests that don't care about cost prices — applies the defaults. */
  public GenerationProperties(
      String model, double temperature, int contextBudget, int historyTurns) {
    this(model, temperature, contextBudget, historyTurns, new Cost(0.25, 2.00));
  }

  /**
   * Token prices for the cost estimate logged per request. Defaults are gpt-5-mini's published
   * standard rates as of 2025-08 ($0.25 / 1M input, $2.00 / 1M output). They are plain config:
   * Atlas does not query a pricing API, so a model or price change means editing these values.
   */
  public record Cost(
      @DefaultValue("0.25") double inputPricePerMillionTokens,
      @DefaultValue("2.00") double outputPricePerMillionTokens) {

    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    public Cost {
      if (inputPricePerMillionTokens < 0 || outputPricePerMillionTokens < 0) {
        throw new IllegalArgumentException("atlas.generation.cost prices must not be negative");
      }
    }

    /**
     * Approximate USD cost of a request from its real token usage. Null token counts (a provider
     * that didn't report them) are treated as zero. Pure — no I/O — so the arithmetic is
     * unit-tested.
     */
    public double estimateUsd(Integer promptTokens, Integer completionTokens) {
      long input = promptTokens != null ? promptTokens : 0;
      long output = completionTokens != null ? completionTokens : 0;
      return input / TOKENS_PER_MILLION * inputPricePerMillionTokens
          + output / TOKENS_PER_MILLION * outputPricePerMillionTokens;
    }
  }
}
