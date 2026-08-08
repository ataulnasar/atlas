package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one completed chat turn — the user message, then the assistant message with its citations
 * JSONB — in a single transaction, and bumps the conversation's {@code updated_at}.
 *
 * <p>A separate bean (not a method on {@code ChatService}) so the {@link Transactional} boundary is
 * honoured: it must be crossed through a Spring proxy, which a self-invocation inside ChatService
 * would bypass. Persistence runs only <em>after</em> a successful generation, so a generation
 * failure never persists a user turn on its own — the two messages (and, for a new conversation,
 * the conversation row) all land together or not at all.
 */
@Component
class TurnPersistence {

  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;

  TurnPersistence(
      ConversationRepository conversationRepository, MessageRepository messageRepository) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
  }

  /**
   * Persists the turn against {@code existingConversationId}, or against a freshly created
   * conversation when it is null. Returns the resolved conversation id.
   */
  @Transactional
  UUID persistTurn(
      UUID existingConversationId, String question, String answer, List<Citation> citations) {
    UUID conversationId =
        existingConversationId != null
            ? existingConversationId
            : conversationRepository.create().id();
    messageRepository.append(conversationId, MessageRole.USER, question, null);
    messageRepository.append(conversationId, MessageRole.ASSISTANT, answer, citations);
    conversationRepository.touch(conversationId);
    return conversationId;
  }
}
