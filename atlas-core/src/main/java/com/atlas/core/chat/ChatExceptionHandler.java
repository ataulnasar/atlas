package com.atlas.core.chat;

import com.atlas.core.document.ApiError;
import com.atlas.core.generation.GenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps chat-flow exceptions to clean, stable error bodies. */
@RestControllerAdvice(basePackageClasses = ChatController.class)
class ChatExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

  @ExceptionHandler(ConversationNotFoundException.class)
  ResponseEntity<ApiError> handleConversationNotFound(ConversationNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("conversation_not_found", e.getMessage()));
  }

  @ExceptionHandler(GenerationException.class)
  ResponseEntity<ApiError> handleGenerationFailure(GenerationException e) {
    // Log the cause server-side; return a generic body so no provider payload leaks to the caller.
    log.warn("Chat generation failed", e);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            new ApiError("generation_failed", "The answer generator failed to produce a response"));
  }
}
