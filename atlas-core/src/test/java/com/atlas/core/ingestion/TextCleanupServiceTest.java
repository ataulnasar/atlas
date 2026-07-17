package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.core.ingestion.TextCleanupService.CleanupResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextCleanupServiceTest {

  @Test
  void emptyPatternListIsANoOp() {
    ParsedDocument document = onePageDocument("First line.\nSECOND LINE NOISE\nThird line.");

    CleanupResult result = service(List.of()).clean(document);

    assertThat(result.strippedLineCount()).isZero();
    assertThat(result.document()).isSameAs(document);
  }

  @Test
  void linesFullyMatchingAPatternAreRemoved() {
    ParsedDocument document =
        onePageDocument("Real content line one.\nFOOTER 2024\nReal content line two.");

    CleanupResult result = service(List.of("FOOTER \\d{4}")).clean(document);

    assertThat(result.strippedLineCount()).isEqualTo(1);
    assertThat(result.document().pages().get(0).text())
        .isEqualTo("Real content line one.\nReal content line two.")
        .doesNotContain("FOOTER");
  }

  @Test
  void aPartialMatchWithinALineDoesNotStripTheWholeLine() {
    ParsedDocument document = onePageDocument("Real content mentioning FOOTER 2024 inline.");

    CleanupResult result = service(List.of("FOOTER \\d{4}")).clean(document);

    assertThat(result.strippedLineCount()).isZero();
    assertThat(result.document().pages().get(0).text())
        .isEqualTo("Real content mentioning FOOTER 2024 inline.");
  }

  @Test
  void nonMatchingLinesArePreservedInOrder() {
    ParsedDocument document = onePageDocument("Alpha.\nBeta.\nGamma.");

    CleanupResult result = service(List.of("NOTHING_MATCHES_THIS")).clean(document);

    assertThat(result.strippedLineCount()).isZero();
    assertThat(result.document().pages().get(0).text()).isEqualTo("Alpha.\nBeta.\nGamma.");
  }

  @Test
  void strippingALineCollapsesTheBlankLineRunItLeavesBehind() {
    // A footer line already surrounded by blank lines (e.g. sitting at a page boundary) must not
    // leave a doubled paragraph gap once it's removed.
    ParsedDocument document =
        onePageDocument("End of paragraph one.\n\nFOOTER\n\nStart of paragraph two.");

    CleanupResult result = service(List.of("FOOTER")).clean(document);

    assertThat(result.document().pages().get(0).text())
        .isEqualTo("End of paragraph one.\n\nStart of paragraph two.");
  }

  @Test
  void multiplePatternsEachStripTheirOwnLines() {
    ParsedDocument document =
        onePageDocument("Kept line.\nOLD STYLE FOOTER\nNEW STYLE FOOTER\nKept line two.");

    CleanupResult result = service(List.of("OLD STYLE FOOTER", "NEW STYLE FOOTER")).clean(document);

    assertThat(result.strippedLineCount()).isEqualTo(2);
    assertThat(result.document().pages().get(0).text()).isEqualTo("Kept line.\nKept line two.");
  }

  @Test
  void strippedLineCountSumsAcrossAllPages() {
    ParsedDocument document =
        new ParsedDocument(
            "",
            List.of(
                new ParsedPage(1, "Page one content.\nFOOTER"),
                new ParsedPage(2, "FOOTER\nPage two content.")));

    CleanupResult result = service(List.of("FOOTER")).clean(document);

    assertThat(result.strippedLineCount()).isEqualTo(2);
    assertThat(result.document().pages().get(0).text()).isEqualTo("Page one content.");
    assertThat(result.document().pages().get(1).text()).isEqualTo("Page two content.");
  }

  @Test
  void eurLexDemoPatternsStripBothFooterStylesSeenInTheCorpusSpotCheckWithoutTouchingRealContent() {
    // The exact patterns documented in docker/.env.example and corpus/README.md, verified
    // against the exact footer strings pulled from the corpus spot check (old OJ-style pagination
    // in varying field order, and the newer ELI-style two-line footer), plus content that must
    // survive untouched: a citation using the abbreviated "OJ" form (not the spelled-out phrase),
    // and an ordinary body sentence.
    List<String> demoPatterns =
        List.of(
            "^(?=.*Official Journal of the European Union)(?=.*\\bEN\\b)(?=.*L\\s*\\d+/\\d+)(?=.*\\d\\d?\\.\\d\\d?\\.\\d{4}).*$",
            "^EN OJ L.? \\d\\d?\\.\\d\\d?\\.\\d{4}$", ".*ELI: http://data\\.europa\\.eu/eli/.*");

    ParsedDocument document =
        onePageDocument(
            String.join(
                "\n",
                "4.5.2016 L 119/39 Official Journal of the European Union EN",
                "EN L 150/116 Official Journal of the European Union 9.6.2023",
                "EN 9.6.2023 Official Journal of the European Union L 150/199",
                "EN OJ L, 12.7.2024",
                "46/144 ELI: http://data.europa.eu/eli/reg/2024/1689/oj",
                "( 27 ) Directive 2006/123/EC of the European Parliament and of the Council of 12"
                    + " December 2006 on services in the internal market (OJ L 376, 27.12.2006, p."
                    + " 36).",
                "2. The controller shall facilitate the exercise of data subject rights under"
                    + " Articles 15 to 22."));

    CleanupResult result = service(demoPatterns).clean(document);

    assertThat(result.strippedLineCount()).isEqualTo(5);
    String remaining = result.document().pages().get(0).text();
    assertThat(remaining)
        .contains("Directive 2006/123/EC")
        .contains("The controller shall facilitate")
        .doesNotContain("Official Journal of the European Union")
        .doesNotContain("EN OJ L,")
        .doesNotContain("ELI: http://data.europa.eu/eli/");
  }

  private TextCleanupService service(List<String> patterns) {
    return new TextCleanupService(new ParsingProperties(patterns));
  }

  private ParsedDocument onePageDocument(String text) {
    return new ParsedDocument(text, List.of(new ParsedPage(1, text)));
  }
}
