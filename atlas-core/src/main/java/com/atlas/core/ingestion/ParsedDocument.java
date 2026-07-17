package com.atlas.core.ingestion;

import java.util.List;

/**
 * The result of parsing a stored document: the full extracted text plus its page breakdown. Formats
 * without a native page concept (e.g. TXT) are represented as a single page.
 */
public record ParsedDocument(String fullText, List<ParsedPage> pages) {

  public int pageCount() {
    return pages.size();
  }
}
