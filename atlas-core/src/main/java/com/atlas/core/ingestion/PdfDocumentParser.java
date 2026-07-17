package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts page-aware text from PDFs using Apache PDFBox. A page with no extractable text layer
 * (e.g. a scanned page — OCR is a documented v1 exclusion, see docs/adr/0006) yields an empty page
 * rather than failing the whole document.
 */
@Component
class PdfDocumentParser implements DocumentParser {

  @Override
  public boolean supports(SupportedContentType contentType) {
    return contentType == SupportedContentType.PDF;
  }

  @Override
  public ParsedDocument parse(Path source) {
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      List<ParsedPage> pages = new ArrayList<>();
      PDFTextStripper stripper = new PDFTextStripper();
      int pageCount = document.getNumberOfPages();
      for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        pages.add(new ParsedPage(pageNumber, stripper.getText(document)));
      }
      String fullText = pages.stream().map(ParsedPage::text).collect(Collectors.joining("\n\n"));
      return new ParsedDocument(fullText, pages);
    } catch (InvalidPasswordException e) {
      throw new CorruptDocumentException(
          SupportedContentType.PDF, "PDF is encrypted and could not be opened: " + source, e);
    } catch (IOException e) {
      throw new CorruptDocumentException(
          SupportedContentType.PDF, "Failed to read PDF file: " + source, e);
    }
  }
}
