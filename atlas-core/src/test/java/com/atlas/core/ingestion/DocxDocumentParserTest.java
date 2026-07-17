package com.atlas.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.core.document.SupportedContentType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxDocumentParserTest {

  @TempDir private Path tempDir;

  private final DocxDocumentParser parser = new DocxDocumentParser();

  @Test
  void supportsOnlyDocx() {
    assertThat(parser.supports(SupportedContentType.DOCX)).isTrue();
    assertThat(parser.supports(SupportedContentType.PDF)).isFalse();
    assertThat(parser.supports(SupportedContentType.TXT)).isFalse();
  }

  @Test
  void extractsParagraphTextAsASinglePage() throws IOException {
    Path docx = tempDir.resolve("paragraphs.docx");
    try (XWPFDocument document = new XWPFDocument()) {
      addParagraph(document, "First paragraph");
      addParagraph(document, "Second paragraph");
      addParagraph(document, "Third paragraph");
      write(document, docx);
    }

    ParsedDocument parsed = parser.parse(docx);

    assertThat(parsed.pageCount()).isEqualTo(1);
    assertThat(parsed.pages().get(0).pageNumber()).isEqualTo(1);
    assertThat(parsed.fullText())
        .contains("First paragraph", "Second paragraph", "Third paragraph");
    assertThat(parsed.pages().get(0).text()).isEqualTo(parsed.fullText());
  }

  @Test
  void includesTableCellTextInReadingOrder() throws IOException {
    Path docx = tempDir.resolve("with-table.docx");
    try (XWPFDocument document = new XWPFDocument()) {
      addParagraph(document, "Before table");
      XWPFTable table = document.createTable(2, 2);
      table.getRow(0).getCell(0).setText("Row1Col1");
      table.getRow(0).getCell(1).setText("Row1Col2");
      table.getRow(1).getCell(0).setText("Row2Col1");
      table.getRow(1).getCell(1).setText("Row2Col2");
      addParagraph(document, "After table");
      write(document, docx);
    }

    ParsedDocument parsed = parser.parse(docx);
    String text = parsed.fullText();

    assertThat(text)
        .contains("Before table", "Row1Col1", "Row1Col2", "Row2Col1", "Row2Col2", "After table");
    assertThat(text.indexOf("Before table")).isLessThan(text.indexOf("Row1Col1"));
    assertThat(text.indexOf("Row1Col2")).isLessThan(text.indexOf("Row2Col1"));
    assertThat(text.indexOf("Row2Col2")).isLessThan(text.indexOf("After table"));
  }

  @Test
  void throwsCorruptDocumentExceptionForGarbageBytes() throws IOException {
    Path garbage = tempDir.resolve("garbage.docx");
    Files.write(garbage, "this is not a zip file at all".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> parser.parse(garbage))
        .isInstanceOf(CorruptDocumentException.class)
        .satisfies(
            e ->
                assertThat(((CorruptDocumentException) e).contentType())
                    .isEqualTo(SupportedContentType.DOCX));
  }

  @Test
  void throwsCorruptDocumentExceptionForAZipThatIsNotADocx() throws IOException {
    Path notADocx = tempDir.resolve("not-a-docx.docx");
    try (OutputStream out = Files.newOutputStream(notADocx);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("hello.txt"));
      zip.write("just a regular zip entry, not an OOXML package".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    assertThatThrownBy(() -> parser.parse(notADocx))
        .isInstanceOf(CorruptDocumentException.class)
        .satisfies(
            e ->
                assertThat(((CorruptDocumentException) e).contentType())
                    .isEqualTo(SupportedContentType.DOCX));
  }

  private void addParagraph(XWPFDocument document, String text) {
    XWPFParagraph paragraph = document.createParagraph();
    XWPFRun run = paragraph.createRun();
    run.setText(text);
  }

  private void write(XWPFDocument document, Path destination) throws IOException {
    try (OutputStream out = Files.newOutputStream(destination)) {
      document.write(out);
    }
  }
}
