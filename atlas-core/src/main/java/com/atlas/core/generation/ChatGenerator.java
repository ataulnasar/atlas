package com.atlas.core.generation;

import java.util.function.Consumer;

/**
 * Atlas's own thin interface over a chat-completion provider — the generation counterpart to {@code
 * EmbeddingService}. Per ADR 0002, application code depends on this, never directly on Spring AI's
 * {@code ChatModel}/{@code ChatClient}, so a provider or Spring AI API change is absorbed in one
 * place ({@link SpringAiChatGenerator}).
 *
 * <p>Only present as a bean when a provider is configured (an API key). In keyless mode there is no
 * generator, so the chat endpoint reports itself unavailable rather than degrading — retrieval can
 * fall back to keyword-only search, but there is nothing to generate an answer with.
 */
public interface ChatGenerator {

  /**
   * Generates an answer for the given system and user prompts in one blocking call. Throws {@link
   * GenerationException} if the underlying provider call fails — the exception message never
   * carries the provider payload.
   */
  GenerationResult generate(String systemPrompt, String userPrompt);

  /**
   * Generates an answer, delivering each text delta to {@code onToken} as it arrives from the
   * provider, and returning the fully aggregated {@link GenerationResult} (complete text + usage)
   * once the stream completes. The deltas carry the model's <em>raw</em> output, including whatever
   * [cN] markers it emitted against the offered source labels; citation reconciliation/renumbering
   * happens on the complete text after this returns (see {@code CitationExtractor}), never on the
   * deltas.
   *
   * <p>Runs blocking-style on the calling (virtual) thread per ADR 0001 — the streaming call path
   * does not require WebFlux. Throws {@link GenerationException} on provider failure, whether
   * before the first delta or partway through; a partial failure has still delivered some deltas to
   * {@code onToken}, so callers must treat a throw as "discard this turn" (no persistence).
   */
  GenerationResult generateStreaming(
      String systemPrompt, String userPrompt, Consumer<String> onToken);
}
