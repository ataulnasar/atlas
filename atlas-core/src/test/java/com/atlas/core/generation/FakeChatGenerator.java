package com.atlas.core.generation;

/**
 * Deterministic in-memory {@link ChatGenerator} for tests — no API key, no network. Returns a
 * canned answer (set per test, typically carrying [cN] markers), reports fixed token usage, and
 * captures the last prompts it was called with so a test can assert what the RAG loop assembled
 * (e.g. that a second turn's prompt replays the conversation history). {@link #setFailing(boolean)}
 * drives the provider-failure path.
 */
public class FakeChatGenerator implements ChatGenerator {

  public static final int PROMPT_TOKENS = 11;
  public static final int COMPLETION_TOKENS = 22;
  public static final int TOTAL_TOKENS = 33;
  public static final String MODEL = "fake-model";

  private volatile String cannedAnswer = "";
  private volatile boolean failing;
  private volatile String lastSystemPrompt;
  private volatile String lastUserPrompt;

  public void setAnswer(String cannedAnswer) {
    this.cannedAnswer = cannedAnswer;
  }

  public void setFailing(boolean failing) {
    this.failing = failing;
  }

  public String lastSystemPrompt() {
    return lastSystemPrompt;
  }

  public String lastUserPrompt() {
    return lastUserPrompt;
  }

  @Override
  public GenerationResult generate(String systemPrompt, String userPrompt) {
    this.lastSystemPrompt = systemPrompt;
    this.lastUserPrompt = userPrompt;
    if (failing) {
      throw new GenerationException(
          "simulated generation failure", new RuntimeException("fake provider is down"));
    }
    return new GenerationResult(
        cannedAnswer, PROMPT_TOKENS, COMPLETION_TOKENS, TOTAL_TOKENS, MODEL);
  }
}
