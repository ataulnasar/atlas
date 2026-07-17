package com.atlas.core.document;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

  DocumentNotFoundException(UUID documentId) {
    super("No document found with id: " + documentId);
  }
}
