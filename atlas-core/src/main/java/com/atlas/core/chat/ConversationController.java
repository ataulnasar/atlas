package com.atlas.core.chat;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(security): unauthenticated for now — like every /api/** endpoint, this must require the
// X-API-Key header once "Add API key authentication" (docs/plan.md, Phase 3) lands. There is no
// per-user ownership yet, which is also why only read-by-id exists (no conversation list).
@RestController
@RequestMapping("/api/conversations")
class ConversationController {

  private final ConversationService conversationService;

  ConversationController(ConversationService conversationService) {
    this.conversationService = conversationService;
  }

  /** Ordered turns of a conversation; 404 (conversation_not_found) for an unknown id. */
  @GetMapping("/{id}")
  ResponseEntity<ConversationResponse> getConversation(@PathVariable UUID id) {
    return ResponseEntity.ok(conversationService.get(id));
  }
}
