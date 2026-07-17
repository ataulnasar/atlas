package com.atlas.core.document;

import java.util.Arrays;
import java.util.Optional;

/** The v1 upload allow-list: PDF, DOCX, TXT. */
public enum SupportedContentType {
  PDF("application/pdf", "pdf"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
  TXT("text/plain", "txt");

  private final String mimeType;
  private final String extension;

  SupportedContentType(String mimeType, String extension) {
    this.mimeType = mimeType;
    this.extension = extension;
  }

  public static Optional<SupportedContentType> fromMimeType(String mimeType) {
    return Arrays.stream(values())
        .filter(type -> type.mimeType.equalsIgnoreCase(mimeType))
        .findFirst();
  }

  /** File extension for the generated on-disk filename — never derived from user input. */
  public String extension() {
    return extension;
  }
}
