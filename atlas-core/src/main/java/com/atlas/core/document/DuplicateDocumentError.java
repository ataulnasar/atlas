package com.atlas.core.document;

import java.util.UUID;

/** Error body for a 409 duplicate-content rejection, identifying the existing document. */
public record DuplicateDocumentError(String error, String message, UUID existingDocumentId) {}
