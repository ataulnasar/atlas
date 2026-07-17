package com.atlas.core.document;

/** Mirrors the CHECK constraint on {@code document.status} in V2__document_and_chunk_schema.sql. */
public enum DocumentStatus {
  PENDING,
  PROCESSING,
  READY,
  FAILED
}
