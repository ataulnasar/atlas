package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Trivial passthrough parser for TXT: the whole file, read as UTF-8, is a single page. */
@Component
class TxtDocumentParser implements DocumentParser {

  @Override
  public boolean supports(SupportedContentType contentType) {
    return contentType == SupportedContentType.TXT;
  }

  @Override
  public ParsedDocument parse(Path source) {
    String text;
    try {
      text = Files.readString(source, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CorruptDocumentException(
          SupportedContentType.TXT, "Failed to read text file: " + source, e);
    }
    return new ParsedDocument(text, List.of(new ParsedPage(1, text)));
  }
}
