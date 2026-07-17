package com.atlas.core.document;

import java.util.Arrays;
import java.util.Optional;

/** The v1 upload allow-list: PDF, DOCX, TXT. */
public enum SupportedContentType {
  PDF("application/pdf"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  TXT("text/plain");

  private final String mimeType;

  SupportedContentType(String mimeType) {
    this.mimeType = mimeType;
  }

  public static Optional<SupportedContentType> fromMimeType(String mimeType) {
    return Arrays.stream(values())
        .filter(type -> type.mimeType.equalsIgnoreCase(mimeType))
        .findFirst();
  }
}
