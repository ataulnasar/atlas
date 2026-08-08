package com.atlas.core.chat;

import com.atlas.core.document.ApiError;
import java.util.Optional;

/**
 * Request-shape validation for the chat endpoint, kept separate from (not-yet-written) endpoint
 * logic so it's testable on its own. Mirrors search's contract: a null or blank question is
 * rejected, and the future controller returns the resulting {@link ApiError} as a 400 — the same
 * status and body shape search uses for a blank query.
 */
public final class ChatRequestValidation {

  private ChatRequestValidation() {}

  /**
   * Returns an {@link ApiError} describing why the request is invalid, or empty if it's acceptable.
   * The caller (the endpoint) maps a present error to {@code 400 Bad Request}.
   */
  public static Optional<ApiError> validate(ChatRequest request) {
    if (request == null || request.question() == null || request.question().isBlank()) {
      return Optional.of(new ApiError("invalid_question", "question must not be blank"));
    }
    return Optional.empty();
  }
}
