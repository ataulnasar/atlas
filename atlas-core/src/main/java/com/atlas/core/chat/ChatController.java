package com.atlas.core.chat;

import com.atlas.core.document.ApiError;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.generation.ChatGenerator;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(security): unauthenticated for now — like every /api/** endpoint, this must require the
// X-API-Key header once "Add API key authentication" (docs/plan.md, Phase 3) lands.
@RestController
@RequestMapping("/api/chat")
class ChatController {

  private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
  private final ObjectProvider<ChatGenerator> chatGeneratorProvider;
  private final ChatService chatService;

  ChatController(
      ObjectProvider<EmbeddingService> embeddingServiceProvider,
      ObjectProvider<ChatGenerator> chatGeneratorProvider,
      ChatService chatService) {
    this.embeddingServiceProvider = embeddingServiceProvider;
    this.chatGeneratorProvider = chatGeneratorProvider;
    this.chatService = chatService;
  }

  @PostMapping
  ResponseEntity<Object> chat(@RequestBody(required = false) ChatRequest request) {
    Optional<ApiError> invalid = ChatRequestValidation.validate(request);
    if (invalid.isPresent()) {
      return ResponseEntity.badRequest().body(invalid.get());
    }

    // Generation cannot degrade: with no provider there is nothing to answer with. Retrieval still
    // works keyless (search endpoints remain available), so the hint points callers there.
    ChatGenerator generator = chatGeneratorProvider.getIfAvailable();
    if (generator == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              new ApiError(
                  "generation_disabled",
                  "No generation provider is configured (missing API key); chat is unavailable. "
                      + "Search endpoints (/api/search/*) remain available."));
    }

    // May be null (keyless retrieval degrades to keyword-only, like the hybrid search endpoint).
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    ChatResponse response = chatService.chat(request, embeddingService, generator);
    return ResponseEntity.ok(response);
  }
}
