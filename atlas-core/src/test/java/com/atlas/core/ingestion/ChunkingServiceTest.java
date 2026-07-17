package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {

  @Test
  void chunksMultiParagraphTextOnParagraphBoundaries() {
    String p1 = "Alpha paragraph discusses topic Alpha in reasonable detail with several clauses.";
    String p2 = "Beta paragraph discusses topic Beta in reasonable detail with several clauses.";
    String p3 = "Gamma paragraph discusses topic Gamma in reasonable detail with several clauses.";
    String p4 = "Delta paragraph discusses topic Delta in reasonable detail with several clauses.";
    String text = String.join("\n\n", p1, p2, p3, p4);

    List<ChunkCandidate> chunks = service(30, 0).chunk(singlePageDocument(text));

    assertThat(chunks.size()).isGreaterThan(1);
    for (String paragraph : List.of(p1, p2, p3, p4)) {
      assertParagraphWhollyContainedInExactlyOneChunk(chunks, paragraph);
    }
  }

  @Test
  void oversizedSingleParagraphFallsBackToSentenceSplitting() {
    String s1 = "This is sentence number one with enough words to matter.";
    String s2 = "This is sentence number two with enough words to matter.";
    String s3 = "This is sentence number three with enough words to matter.";
    String s4 = "This is sentence number four with enough words to matter.";
    // One paragraph — no blank lines — so paragraph splitting can't help.
    String text = String.join(" ", s1, s2, s3, s4);

    List<ChunkCandidate> chunks = service(20, 0).chunk(singlePageDocument(text));

    assertThat(chunks.size()).isGreaterThan(1);
    for (String sentence : List.of(s1, s2, s3, s4)) {
      assertParagraphWhollyContainedInExactlyOneChunk(chunks, sentence);
    }
  }

  @Test
  void pathologicalNoBoundaryStringHardSplitsWithoutExceedingMaxTokens() {
    String text = "a".repeat(5000); // one giant "word": no spaces, no punctuation anywhere

    List<ChunkCandidate> chunks = service(20, 0).chunk(singlePageDocument(text));

    assertThat(chunks.size()).isGreaterThan(1);
    for (ChunkCandidate chunk : chunks) {
      assertThat(chunk.tokenCount()).isLessThanOrEqualTo(20);
    }
    String reconstructed = chunks.stream().map(ChunkCandidate::content).reduce("", String::concat);
    assertThat(reconstructed).isEqualTo(text);
  }

  @Test
  void overlapCarriesTrailingContextIntoTheNextChunk() {
    List<String> paragraphs = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      paragraphs.add(
          "Paragraph number "
              + i
              + " contains several distinct words to build up token count nicely for testing"
              + " purposes here.");
    }
    String text = String.join("\n\n", paragraphs);

    List<ChunkCandidate> chunks = service(40, 15).chunk(singlePageDocument(text));

    assertThat(chunks.size()).isGreaterThan(1);
    for (int i = 0; i < chunks.size() - 1; i++) {
      String tailOfCurrent = chunks.get(i).content();
      String headOfNext = chunks.get(i + 1).content();
      int overlapLength = longestSuffixPrefixOverlap(tailOfCurrent, headOfNext);
      assertThat(overlapLength)
          .as("chunk %d and chunk %d should share overlapping content", i, i + 1)
          .isGreaterThan(0);
    }
  }

  @Test
  void tracksPageProvenanceAcrossAPageBreak() {
    String page1Text = "Short page one content here.";
    String page2Text = "Short page two content here.";
    ParsedDocument document =
        new ParsedDocument(
            page1Text + "\n\n" + page2Text,
            List.of(new ParsedPage(1, page1Text), new ParsedPage(2, page2Text)));

    // Generous budget so both pages are packed into a single chunk that spans the break.
    List<ChunkCandidate> chunks = service(500, 0).chunk(document);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).startPage()).isEqualTo(1);
    assertThat(chunks.get(0).endPage()).isEqualTo(2);
  }

  @Test
  void singlePageDocumentsYieldChunksOnThatSamePage() {
    String text = "Just a short single-page document.";

    List<ChunkCandidate> chunks = service(500, 0).chunk(singlePageDocument(text));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).startPage()).isEqualTo(1);
    assertThat(chunks.get(0).endPage()).isEqualTo(1);
  }

  @Test
  void everyChunkRespectsTheConfiguredMaxTokens() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      text.append("Paragraph ")
          .append(i)
          .append(
              " has some reasonably long sentence content to build up the token count across"
                  + " many chunks for this invariant test. ")
          .append("It also has a second sentence to add more sentence-boundary complexity here.")
          .append("\n\n");
    }

    List<ChunkCandidate> chunks = service(60, 10).chunk(singlePageDocument(text.toString()));

    assertThat(chunks).isNotEmpty();
    for (ChunkCandidate chunk : chunks) {
      assertThat(chunk.tokenCount()).isLessThanOrEqualTo(60);
    }
  }

  @Test
  void emptyDocumentYieldsNoChunks() {
    List<ChunkCandidate> chunks = service(500, 0).chunk(new ParsedDocument("", List.of()));

    assertThat(chunks).isEmpty();
  }

  private void assertParagraphWhollyContainedInExactlyOneChunk(
      List<ChunkCandidate> chunks, String expectedText) {
    long containingChunks =
        chunks.stream().filter(chunk -> chunk.content().contains(expectedText)).count();
    assertThat(containingChunks)
        .as("expected exactly one chunk to wholly contain: %s", expectedText)
        .isEqualTo(1);
  }

  private int longestSuffixPrefixOverlap(String a, String b) {
    int max = Math.min(a.length(), b.length());
    for (int len = max; len > 0; len--) {
      if (a.endsWith(b.substring(0, len))) {
        return len;
      }
    }
    return 0;
  }

  private ChunkingService service(int maxTokens, int overlapTokens) {
    return new ChunkingService(new ChunkingProperties(maxTokens, overlapTokens));
  }

  private ParsedDocument singlePageDocument(String text) {
    return new ParsedDocument(text, List.of(new ParsedPage(1, text)));
  }
}
