package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link DocumentParser} registered for a document's content type. New parsers (PDF,
 * DOCX) register themselves simply by existing as a {@code DocumentParser} bean — this registry
 * does not need to change as they're added.
 */
@Component
public class DocumentParserRegistry {

  private final List<DocumentParser> parsers;

  DocumentParserRegistry(List<DocumentParser> parsers) {
    this.parsers = List.copyOf(parsers);
  }

  public DocumentParser resolve(SupportedContentType contentType) {
    return parsers.stream()
        .filter(parser -> parser.supports(contentType))
        .findFirst()
        .orElseThrow(() -> new NoParserAvailableException(contentType));
  }
}
