package com.atlas.core.generation;

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
   * Generates an answer for the given system and user prompts. Throws {@link GenerationException}
   * if the underlying provider call fails — the exception message never carries the provider
   * payload.
   */
  GenerationResult generate(String systemPrompt, String userPrompt);
}
