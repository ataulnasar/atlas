package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Extracts text from DOCX files using Apache POI. Paragraphs and table cells are read in document
 * order — policy/legal documents (the v1 demo corpus domain) frequently carry substance in tables,
 * so skipping them would silently drop content. DOCX has no native page concept, so the whole
 * document is returned as a single {@link ParsedPage}, consistent with TXT.
 */
@Component
class DocxDocumentParser implements DocumentParser {

  @Override
  public boolean supports(SupportedContentType contentType) {
    return contentType == SupportedContentType.DOCX;
  }

  @Override
  public ParsedDocument parse(Path source) {
    try (InputStream in = Files.newInputStream(source);
        XWPFDocument document = new XWPFDocument(in)) {
      String text = extractText(document);
      return new ParsedDocument(text, List.of(new ParsedPage(1, text)));
    } catch (IOException | RuntimeException e) {
      // POI signals malformed/wrong-format input through a mix of IOException and unchecked
      // exceptions (POIXMLException, NotOfficeXmlFileException, etc.) — there's no single
      // checked type to catch, so both are treated as a corrupt document here.
      throw new CorruptDocumentException(
          SupportedContentType.DOCX, "Failed to read DOCX file: " + source, e);
    }
  }

  private String extractText(XWPFDocument document) {
    StringBuilder text = new StringBuilder();
    for (IBodyElement element : document.getBodyElements()) {
      if (element instanceof XWPFParagraph paragraph) {
        text.append(paragraph.getText()).append('\n');
      } else if (element instanceof XWPFTable table) {
        appendTableText(text, table);
      }
    }
    return text.toString();
  }

  private void appendTableText(StringBuilder text, XWPFTable table) {
    for (XWPFTableRow row : table.getRows()) {
      for (XWPFTableCell cell : row.getTableCells()) {
        text.append(cell.getText()).append('\t');
      }
      text.append('\n');
    }
  }
}
