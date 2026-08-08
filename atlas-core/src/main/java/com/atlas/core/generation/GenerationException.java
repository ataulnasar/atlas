package com.atlas.core.generation;

/**
 * Wraps any chat-provider failure (network, auth, rate limit, malformed response). The cause is
 * preserved for server-side logging, but the message is deliberately generic: the chat endpoint
 * turns this into a 502 with a clean body and never surfaces the provider's payload to callers.
 */
public class GenerationException extends RuntimeException {

  public GenerationException(String message, Throwable cause) {
    super(message, cause);
  }
}
