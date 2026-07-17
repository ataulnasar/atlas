package com.atlas.core.document;

import java.util.UUID;

public class DuplicateDocumentException extends RuntimeException {

  private final UUID existingDocumentId;

  DuplicateDocumentException(UUID existingDocumentId) {
    super("A document with this content already exists: " + existingDocumentId);
    this.existingDocumentId = existingDocumentId;
  }

  public UUID existingDocumentId() {
    return existingDocumentId;
  }
}
