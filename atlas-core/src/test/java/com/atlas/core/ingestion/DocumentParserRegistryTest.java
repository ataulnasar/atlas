package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.core.document.SupportedContentType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentParserRegistryTest {

  @Test
  void resolvesTheParserThatSupportsTheRequestedContentType() {
    DocumentParser txtParser = fakeParser(SupportedContentType.TXT);
    DocumentParser pdfParser = fakeParser(SupportedContentType.PDF);
    DocumentParserRegistry registry = new DocumentParserRegistry(List.of(txtParser, pdfParser));

    assertThat(registry.resolve(SupportedContentType.TXT)).isSameAs(txtParser);
    assertThat(registry.resolve(SupportedContentType.PDF)).isSameAs(pdfParser);
  }

  @Test
  void throwsNoParserAvailableWhenNothingSupportsTheContentType() {
    DocumentParserRegistry registry =
        new DocumentParserRegistry(List.of(fakeParser(SupportedContentType.TXT)));

    assertThatThrownBy(() -> registry.resolve(SupportedContentType.PDF))
        .isInstanceOf(NoParserAvailableException.class)
        .satisfies(
            e ->
                assertThat(((NoParserAvailableException) e).contentType())
                    .isEqualTo(SupportedContentType.PDF));
  }

  @Test
  void throwsNoParserAvailableWhenNoParsersAreRegisteredAtAll() {
    DocumentParserRegistry registry = new DocumentParserRegistry(List.of());

    assertThatThrownBy(() -> registry.resolve(SupportedContentType.TXT))
        .isInstanceOf(NoParserAvailableException.class);
  }

  private DocumentParser fakeParser(SupportedContentType supportedType) {
    return new DocumentParser() {
      @Override
      public boolean supports(SupportedContentType contentType) {
        return contentType == supportedType;
      }

      @Override
      public ParsedDocument parse(Path source) {
        throw new UnsupportedOperationException("not used in this test");
      }
    };
  }
}
