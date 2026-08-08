package com.atlas.core.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.document.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Offline tests for [cN] marker parsing, renumbering, and answer rewriting. */
class CitationExtractorTest {

  private final CitationExtractor extractor = new CitationExtractor();

  private static Citation offered(String label, String filename) {
    return new Citation(
        label, UUID.randomUUID(), UUID.randomUUID(), filename, filename, 1, 1, "preview");
  }

  private final Citation c1 = offered("c1", "a.pdf");
  private final Citation c2 = offered("c2", "b.pdf");
  private final Citation c3 = offered("c3", "c.pdf");
  private final List<Citation> offered = List.of(c1, c2, c3);

  @Test
  void singleMarkerIsKeptAndItsCitationReturned() {
    CitationExtraction result = extractor.extract("A DPO is required [c1].", offered);

    assertThat(result.answer()).isEqualTo("A DPO is required [c1].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1");
    assertThat(result.citations().get(0).chunkId()).isEqualTo(c1.chunkId());
  }

  @Test
  void multipleSeparateMarkersAreEachReturned() {
    CitationExtraction result = extractor.extract("First [c1]. Second [c2].", offered);

    assertThat(result.answer()).isEqualTo("First [c1]. Second [c2].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
  }

  @Test
  void commaSeparatedFormIsParsedAndNormalizedToAdjacentMarkers() {
    CitationExtraction result = extractor.extract("Both apply [c1, c2].", offered);

    assertThat(result.answer()).isEqualTo("Both apply [c1][c2].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
  }

  @Test
  void adjacentBracketFormIsParsed() {
    CitationExtraction result = extractor.extract("Both apply [c1][c2].", offered);

    assertThat(result.answer()).isEqualTo("Both apply [c1][c2].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
  }

  @Test
  void citationsAreRenumberedStablyInFirstAppearanceOrder() {
    CitationExtraction result = extractor.extract("First [c3] then [c1].", offered);

    // c3 appears first -> becomes c1; c1 appears next -> becomes c2.
    assertThat(result.answer()).isEqualTo("First [c1] then [c2].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1", "c2");
    assertThat(result.citations().get(0).chunkId()).isEqualTo(c3.chunkId());
    assertThat(result.citations().get(1).chunkId()).isEqualTo(c1.chunkId());
  }

  @Test
  void repeatedMarkerRenumbersOnceAndReturnsOneCitation() {
    CitationExtraction result = extractor.extract("A [c2]. Again [c2].", offered);

    assertThat(result.answer()).isEqualTo("A [c1]. Again [c1].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1");
    assertThat(result.citations().get(0).chunkId()).isEqualTo(c2.chunkId());
  }

  @Test
  void unknownLabelIsDroppedFromAnswerAndCitations() {
    CitationExtraction result = extractor.extract("Made up [c9].", List.of(c1));

    assertThat(result.answer()).isEqualTo("Made up .");
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void unknownLabelWithinAGroupIsDroppedButKnownOnesSurvive() {
    CitationExtraction result = extractor.extract("Partly known [c1, c9].", List.of(c1));

    assertThat(result.answer()).isEqualTo("Partly known [c1].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1");
  }

  @Test
  void answerWithNoMarkersComesBackUnchangedWithNoCitations() {
    CitationExtraction result =
        extractor.extract("The documents do not cover this question.", offered);

    assertThat(result.answer()).isEqualTo("The documents do not cover this question.");
    assertThat(result.citations()).isEmpty();
  }

  @Test
  void nonCitationBracketsAreLeftUntouched() {
    CitationExtraction result = extractor.extract("See [appendix] and also [c1].", offered);

    assertThat(result.answer()).isEqualTo("See [appendix] and also [c1].");
    assertThat(result.citations()).extracting(Citation::citationId).containsExactly("c1");
  }
}
