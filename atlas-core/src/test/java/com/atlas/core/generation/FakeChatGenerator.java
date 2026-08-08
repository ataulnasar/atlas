package com.atlas.core.generation;

import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic in-memory {@link ChatGenerator} for tests — no API key, no network. Returns a
 * canned answer (set per test, typically carrying [cN] markers), reports fixed token usage, and
 * captures the last prompts it was called with so a test can assert what the RAG loop assembled
 * (e.g. that a second turn's prompt replays the conversation history). {@link #setFailing(boolean)}
 * drives the provider-failure path.
 *
 * <p>For the streaming path, {@link #setScriptedDeltas(List)} scripts the exact deltas emitted (the
 * aggregated answer is their concatenation); with no script it emits the canned answer as a single
 * delta. {@link #setFailAfterDeltas(int)} makes the stream throw partway through, after emitting
 * that many deltas — the mid-stream-failure case.
 */
public class FakeChatGenerator implements ChatGenerator {

  public static final int PROMPT_TOKENS = 11;
  public static final int COMPLETION_TOKENS = 22;
  public static final int TOTAL_TOKENS = 33;
  public static final String MODEL = "fake-model";

  private volatile String cannedAnswer = "";
  private volatile boolean failing;
  private volatile List<String> scriptedDeltas;
  private volatile int failAfterDeltas = -1;
  private volatile String lastSystemPrompt;
  private volatile String lastUserPrompt;

  public void setAnswer(String cannedAnswer) {
    this.cannedAnswer = cannedAnswer;
  }

  public void setFailing(boolean failing) {
    this.failing = failing;
  }

  /** Scripts the exact deltas the streaming path emits; the answer is their concatenation. */
  public void setScriptedDeltas(List<String> scriptedDeltas) {
    this.scriptedDeltas = scriptedDeltas == null ? null : List.copyOf(scriptedDeltas);
  }

  /** Streaming throws after emitting this many deltas (-1 disables); the mid-stream failure. */
  public void setFailAfterDeltas(int failAfterDeltas) {
    this.failAfterDeltas = failAfterDeltas;
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

  @Override
  public GenerationResult generateStreaming(
      String systemPrompt, String userPrompt, Consumer<String> onToken) {
    this.lastSystemPrompt = systemPrompt;
    this.lastUserPrompt = userPrompt;
    if (failing) {
      throw new GenerationException(
          "simulated generation failure", new RuntimeException("fake provider is down"));
    }
    List<String> deltas = scriptedDeltas != null ? scriptedDeltas : List.of(cannedAnswer);
    StringBuilder answer = new StringBuilder();
    for (int i = 0; i < deltas.size(); i++) {
      if (i == failAfterDeltas) {
        throw new GenerationException(
            "simulated mid-stream failure",
            new RuntimeException("fake provider dropped mid-stream"));
      }
      String delta = deltas.get(i);
      answer.append(delta);
      onToken.accept(delta);
    }
    return new GenerationResult(
        answer.toString(), PROMPT_TOKENS, COMPLETION_TOKENS, TOTAL_TOKENS, MODEL);
  }
}
