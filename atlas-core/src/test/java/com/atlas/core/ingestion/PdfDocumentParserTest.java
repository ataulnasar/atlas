package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.core.document.SupportedContentType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfDocumentParserTest {

  @TempDir private Path tempDir;

  private final PdfDocumentParser parser = new PdfDocumentParser();

  @Test
  void supportsOnlyPdf() {
    assertThat(parser.supports(SupportedContentType.PDF)).isTrue();
    assertThat(parser.supports(SupportedContentType.DOCX)).isFalse();
    assertThat(parser.supports(SupportedContentType.TXT)).isFalse();
  }

  @Test
  void parsesEachPageWithTheCorrectPageNumberAndText() throws IOException {
    Path pdf = tempDir.resolve("multi-page.pdf");
    writePdf(pdf, "Page one content", "Page two content", "Page three content");

    ParsedDocument parsed = parser.parse(pdf);

    assertThat(parsed.pageCount()).isEqualTo(3);
    assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
    assertThat(parsed.pages().get(0).text()).contains("Page one content");
    assertThat(parsed.pages().get(1).pageNumber()).isEqualTo(2);
    assertThat(parsed.pages().get(1).text()).contains("Page two content");
    assertThat(parsed.pages().get(2).pageNumber()).isEqualTo(3);
    assertThat(parsed.pages().get(2).text()).contains("Page three content");
    assertThat(parsed.fullText())
        .contains("Page one content", "Page two content", "Page three content");
  }

  @Test
  void aPageWithNoTextLayerYieldsAnEmptyPageRatherThanFailing() throws IOException {
    Path pdf = tempDir.resolve("blank-page.pdf");
    try (PDDocument document = new PDDocument()) {
      addTextPage(document, "Page with text");
      document.addPage(new PDPage()); // no content stream at all — simulates a scanned page
      document.save(pdf.toFile());
    }

    ParsedDocument parsed = parser.parse(pdf);

    assertThat(parsed.pageCount()).isEqualTo(2);
    assertThat(parsed.pages().get(0).text()).contains("Page with text");
    assertThat(parsed.pages().get(1).text()).isBlank();
  }

  @Test
  void throwsCorruptDocumentExceptionForATruncatedPdf() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PDDocument document = new PDDocument()) {
      addTextPage(document, "will be truncated");
      document.save(buffer);
    }
    byte[] validBytes = buffer.toByteArray();
    byte[] truncated = new byte[validBytes.length / 2];
    System.arraycopy(validBytes, 0, truncated, 0, truncated.length);

    Path corrupt = tempDir.resolve("truncated.pdf");
    Files.write(corrupt, truncated);

    assertThatThrownBy(() -> parser.parse(corrupt))
        .isInstanceOf(CorruptDocumentException.class)
        .satisfies(
            e ->
                assertThat(((CorruptDocumentException) e).contentType())
                    .isEqualTo(SupportedContentType.PDF));
  }

  @Test
  void throwsCorruptDocumentExceptionForAnEncryptedPdf() throws IOException {
    Path encrypted = tempDir.resolve("encrypted.pdf");
    try (PDDocument document = new PDDocument()) {
      addTextPage(document, "secret content");
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
      policy.setEncryptionKeyLength(128);
      document.protect(policy);
      document.save(encrypted.toFile());
    }

    assertThatThrownBy(() -> parser.parse(encrypted))
        .isInstanceOf(CorruptDocumentException.class)
        .satisfies(
            e ->
                assertThat(((CorruptDocumentException) e).contentType())
                    .isEqualTo(SupportedContentType.PDF));
  }

  private void writePdf(Path destination, String... pageTexts) throws IOException {
    try (PDDocument document = new PDDocument()) {
      for (String pageText : pageTexts) {
        addTextPage(document, pageText);
      }
      document.save(destination.toFile());
    }
  }

  private void addTextPage(PDDocument document, String text) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
      contentStream.beginText();
      contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      contentStream.newLineAtOffset(50, 700);
      contentStream.showText(text);
      contentStream.endText();
    }
  }
}
