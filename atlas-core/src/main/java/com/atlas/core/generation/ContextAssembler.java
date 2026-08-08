package com.atlas.core.generation;

import com.atlas.core.document.Citation;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders ranked retrieved chunks into the prompt's sources block and enforces the token budget.
 *
 * <p>Chunks are walked in rank order and rendered as
 *
 * <pre>[cN] {filename}, pp. {start}–{end}:
 * {chunk content}</pre>
 *
 * blocks separated by a blank line. Budgeting is by the stored {@code token_count}: a chunk whose
 * tokens would push the running total past the budget is dropped whole (chunks are never truncated
 * mid-way), and scanning continues so a smaller lower-ranked chunk can still fit. Because a chunk
 * that overflows the budget on its own is simply dropped, an all-too-large candidate set can yield
 * an empty context — a legitimate outcome that steers the model to abstain.
 *
 * <p>The c1..cN labels are assigned over the chunks that survive budgeting, in rank order, so they
 * line up with the returned {@link Citation} list and the [cN] markers in the rendered text.
 */
@Component
public class ContextAssembler {

  private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

  private final GenerationProperties properties;

  ContextAssembler(GenerationProperties properties) {
    this.properties = properties;
  }

  /** Assembles context using the configured {@code atlas.generation.context-budget}. */
  public AssembledContext assemble(List<RetrievedChunk> ranked) {
    return assemble(ranked, properties.contextBudget());
  }

  /** Assembles context under an explicit token budget. */
  public AssembledContext assemble(List<RetrievedChunk> ranked, int contextBudgetTokens) {
    List<Citation> citations = new ArrayList<>();
    List<String> blocks = new ArrayList<>();
    int tokensUsed = 0;

    for (RetrievedChunk chunk : ranked) {
      if (tokensUsed + chunk.tokenCount() > contextBudgetTokens) {
        continue; // Doesn't fit — drop it whole, but a smaller later chunk may still fit.
      }
      String citationId = "c" + (citations.size() + 1);
      Citation citation = chunk.citation().withCitationId(citationId);
      citations.add(citation);
      blocks.add(render(citationId, citation, chunk.content()));
      tokensUsed += chunk.tokenCount();
    }

    log.debug(
        "Context assembly: offered {} chunks, included {}, {}/{} budget tokens used",
        ranked.size(),
        citations.size(),
        tokensUsed,
        contextBudgetTokens);

    return new AssembledContext(String.join("\n\n", blocks), List.copyOf(citations));
  }

  private static String render(String citationId, Citation citation, String content) {
    return "["
        + citationId
        + "] "
        + citation.documentFilename()
        + ", pp. "
        + citation.startPage()
        + "–"
        + citation.endPage()
        + ":\n"
        + content;
  }
}
