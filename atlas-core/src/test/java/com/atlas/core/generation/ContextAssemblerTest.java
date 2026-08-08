package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Offline tests for context rendering and token-budget enforcement. */
class ContextAssemblerTest {

  private final ContextAssembler assembler =
      new ContextAssembler(new GenerationProperties("gpt-5-mini", 0.1, 6000, 3));

  private static RetrievedChunk chunk(
      String filename, int start, int end, String content, int tokens) {
    Citation citation =
        new Citation(
            "ignored",
            UUID.randomUUID(),
            UUID.randomUUID(),
            filename,
            filename,
            start,
            end,
            "preview");
    return new RetrievedChunk(citation, content, tokens);
  }

  @Test
  void rendersEachChunkInTheExactCitedSourceFormat() {
    AssembledContext assembled =
        assembler.assemble(
            List.of(
                chunk("gdpr.pdf", 12, 13, "A data protection officer is required.", 10),
                chunk("ccpa.pdf", 4, 4, "Consumers may opt out.", 8)),
            1000);

    assertThat(assembled.sources())
        .isEqualTo(
            "[c1] gdpr.pdf, pp. 12–13:\n"
                + "A data protection officer is required.\n"
                + "\n"
                + "[c2] ccpa.pdf, pp. 4–4:\n"
                + "Consumers may opt out.");

    assertThat(assembled.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
    assertThat(assembled.citations())
        .extracting(Citation::documentFilename)
        .containsExactly("gdpr.pdf", "ccpa.pdf");
  }

  @Test
  void dropsTheLowestRankedChunksThatDoNotFitTheBudget() {
    AssembledContext assembled =
        assembler.assemble(
            List.of(
                chunk("a.pdf", 1, 1, "first", 100),
                chunk("b.pdf", 2, 2, "second", 100),
                chunk("c.pdf", 3, 3, "third", 100)),
            250);

    // 100 + 100 fit; the third would reach 300 > 250, so it is dropped whole. Order preserved.
    assertThat(assembled.citations())
        .extracting(Citation::documentFilename)
        .containsExactly("a.pdf", "b.pdf");
    assertThat(assembled.sources()).contains("first").contains("second").doesNotContain("third");
  }

  @Test
  void skipsAnOversizeChunkButStillFitsASmallerLowerRankedOne() {
    AssembledContext assembled =
        assembler.assemble(
            List.of(
                chunk("a.pdf", 1, 1, "kept-high", 100),
                chunk("b.pdf", 2, 2, "too-big", 5000),
                chunk("c.pdf", 3, 3, "kept-low", 50)),
            200);

    // The 5000-token chunk never fits and is dropped; the small third chunk still does.
    assertThat(assembled.citations())
        .extracting(Citation::documentFilename)
        .containsExactly("a.pdf", "c.pdf");
    // Labels are assigned over the surviving set, in rank order.
    assertThat(assembled.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
    assertThat(assembled.sources())
        .contains("kept-high")
        .contains("kept-low")
        .doesNotContain("too-big");
  }

  @Test
  void aChunkLargerThanTheWholeBudgetYieldsEmptyContext() {
    AssembledContext assembled =
        assembler.assemble(List.of(chunk("a.pdf", 1, 1, "huge", 9000)), 6000);

    assertThat(assembled.citations()).isEmpty();
    assertThat(assembled.sources()).isEmpty();
  }

  @Test
  void assembleWithoutExplicitBudgetUsesConfiguredBudget() {
    // Configured budget is 6000; a 5000-token chunk fits, a further 2000-token one would not.
    AssembledContext assembled =
        assembler.assemble(
            List.of(chunk("a.pdf", 1, 1, "fits", 5000), chunk("b.pdf", 2, 2, "overflow", 2000)));

    assertThat(assembled.citations())
        .extracting(Citation::documentFilename)
        .containsExactly("a.pdf");
  }
}
