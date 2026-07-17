package com.atlas.core.document;

public class DocumentTooLargeException extends RuntimeException {

  DocumentTooLargeException(long actualBytes, long maxBytes) {
    super("File size " + actualBytes + " bytes exceeds the maximum of " + maxBytes + " bytes");
  }
}
