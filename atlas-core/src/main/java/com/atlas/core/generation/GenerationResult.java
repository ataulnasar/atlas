package com.atlas.core.generation;

/**
 * The outcome of one generation call: the model's raw {@code text} (still carrying the [cN] markers
 * it chose, before {@code CitationExtractor} reconciles them) and the provider's token accounting.
 * Any of the token fields or {@code model} may be null if the provider didn't report them.
 */
public record GenerationResult(
    String text,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    String model) {}
