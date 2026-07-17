package com.atlas.core.ingestion;

/**
 * Base type for document-parsing failures. Sealed to keep the failure taxonomy closed and
 * exhaustively matchable: either no parser is registered for the content type at all ({@link
 * NoParserAvailableException}), or a parser attempted the file and it was malformed/unreadable
 * ({@link CorruptDocumentException}).
 */
public sealed class ParseException extends RuntimeException
    permits NoParserAvailableException, CorruptDocumentException {

  ParseException(String message) {
    super(message);
  }

  ParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
