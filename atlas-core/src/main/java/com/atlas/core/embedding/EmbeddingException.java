package com.atlas.core.embedding;

/** Wraps any embedding-provider failure (network, auth, rate limit, malformed response). */
public class EmbeddingException extends RuntimeException {

  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
