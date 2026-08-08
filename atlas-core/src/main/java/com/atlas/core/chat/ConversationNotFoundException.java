package com.atlas.core.chat;

import java.util.UUID;

/**
 * Thrown when a chat request supplies a {@code conversationId} that doesn't exist. The chat
 * endpoint maps this to 404 — continuing a conversation that was never created (or was deleted) is
 * a client error, distinct from omitting the id to start a fresh one.
 */
public class ConversationNotFoundException extends RuntimeException {

  public ConversationNotFoundException(UUID conversationId) {
    super("Conversation not found: " + conversationId);
  }
}
