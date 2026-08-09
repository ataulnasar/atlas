package com.atlas.core.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads a persisted conversation back for {@code GET /api/conversations/{id}}. */
@Service
class ConversationService {

  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;

  ConversationService(
      ConversationRepository conversationRepository, MessageRepository messageRepository) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
  }

  /**
   * The conversation's turns in order.
   *
   * @throws ConversationNotFoundException if the id is unknown (→ 404)
   */
  ConversationResponse get(UUID conversationId) {
    if (!conversationRepository.exists(conversationId)) {
      throw new ConversationNotFoundException(conversationId);
    }
    List<ConversationMessageView> messages =
        messageRepository.findByConversationId(conversationId).stream()
            .map(
                message ->
                    new ConversationMessageView(
                        message.role().dbValue(),
                        message.content(),
                        message.citations(),
                        message.createdAt()))
            .toList();
    return new ConversationResponse(conversationId, messages);
  }
}
