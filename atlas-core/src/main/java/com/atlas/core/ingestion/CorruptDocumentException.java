package com.atlas.core.ingestion;

import com.atlas.core.document.SupportedContentType;

/** A parser recognized the content type but could not extract text from the file's bytes. */
public final class CorruptDocumentException extends ParseException {

  private final SupportedContentType contentType;

  public CorruptDocumentException(
      SupportedContentType contentType, String message, Throwable cause) {
    super(message, cause);
    this.contentType = contentType;
  }

  public SupportedContentType contentType() {
    return contentType;
  }
}
