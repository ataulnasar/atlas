package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;

/** No {@link DocumentParser} bean declares support for the given content type. */
public final class NoParserAvailableException extends ParseException {

  private final SupportedContentType contentType;

  public NoParserAvailableException(SupportedContentType contentType) {
    super("No parser registered for content type: " + contentType);
    this.contentType = contentType;
  }

  public SupportedContentType contentType() {
    return contentType;
  }
}
