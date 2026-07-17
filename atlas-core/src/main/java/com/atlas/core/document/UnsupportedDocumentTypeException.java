package com.atlas.core.document;

public class UnsupportedDocumentTypeException extends RuntimeException {

  UnsupportedDocumentTypeException(String contentType) {
    super("Unsupported content type: " + contentType);
  }
}
