package com.atlas.core.chat;

import com.atlas.core.document.ApiError;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.generation.ChatGenerator;
import com.atlas.core.generation.GenerationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// TODO(security): unauthenticated for now — like every /api/** endpoint, this must require the
// X-API-Key header once "Add API key authentication" (docs/plan.md, Phase 3) lands.
@RestController
@RequestMapping("/api/chat")
class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  // SSE connections must not hang forever if the client stalls or the model wedges.
  private static final long STREAM_TIMEOUT_MILLIS = 120_000L;

  private static final String GENERATION_DISABLED_MESSAGE =
      "No generation provider is configured (missing API key); chat is unavailable. "
          + "Search endpoints (/api/search/*) remain available.";

  private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
  private final ObjectProvider<ChatGenerator> chatGeneratorProvider;
  private final ChatService chatService;
  private final ExecutorService streamExecutor;

  ChatController(
      ObjectProvider<EmbeddingService> embeddingServiceProvider,
      ObjectProvider<ChatGenerator> chatGeneratorProvider,
      ChatService chatService,
      @Qualifier(ChatStreamConfig.STREAM_EXECUTOR_BEAN) ExecutorService streamExecutor) {
    this.embeddingServiceProvider = embeddingServiceProvider;
    this.chatGeneratorProvider = chatGeneratorProvider;
    this.chatService = chatService;
    this.streamExecutor = streamExecutor;
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
          .body(new ApiError("generation_disabled", GENERATION_DISABLED_MESSAGE));
    }

    // May be null (keyless retrieval degrades to keyword-only, like the hybrid search endpoint).
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    ChatResponse response = chatService.chat(request, embeddingService, generator);
    return ResponseEntity.ok(response);
  }

  /**
   * SSE variant of {@link #chat}: identical RAG loop, streamed. Emits repeated {@code token} events
   * (raw answer deltas, original [cN] markers), then one {@code citations} event (renumbered final
   * answer + cited subset), then one {@code done} event (usage + conversationId). A failure once
   * the stream has started is delivered as a clean {@code error} event; the stream is then closed.
   *
   * <p>Returns {@code Object} so the pre-stream guards — 400 for a blank question, 503 when no
   * generator is configured — come back as ordinary JSON responses <em>before</em> any SSE begins;
   * a success returns the {@link SseEmitter}. Those two guards are the only failures surfaced as
   * HTTP status codes; anything after the stream commits is an {@code error} event.
   */
  @PostMapping("/stream")
  Object chatStream(@RequestBody(required = false) ChatRequest request) {
    Optional<ApiError> invalid = ChatRequestValidation.validate(request);
    if (invalid.isPresent()) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_JSON)
          .body(invalid.get());
    }

    ChatGenerator generator = chatGeneratorProvider.getIfAvailable();
    if (generator == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ApiError("generation_disabled", GENERATION_DISABLED_MESSAGE));
    }

    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
    streamExecutor.execute(() -> stream(emitter, request, embeddingService, generator));
    return emitter;
  }

  private void stream(
      SseEmitter emitter,
      ChatRequest request,
      EmbeddingService embeddingService,
      ChatGenerator generator) {
    try {
      ChatResponse response =
          chatService.chatStreaming(
              request,
              embeddingService,
              generator,
              delta -> send(emitter, "token", new StreamTokenEvent(delta)));
      send(emitter, "citations", new StreamCitationsEvent(response.answer(), response.citations()));
      send(
          emitter,
          "done",
          new StreamDoneEvent(
              response.conversationId(), response.retrievalMode(), response.usage()));
      emitter.complete();
    } catch (ConversationNotFoundException e) {
      failStream(emitter, new ApiError("conversation_not_found", e.getMessage()));
    } catch (GenerationException e) {
      log.warn("Streaming chat generation failed", e);
      failStream(
          emitter,
          new ApiError("generation_failed", "The answer generator failed to produce a response"));
    } catch (UncheckedIOException e) {
      // Client disconnected mid-stream — nothing to report to, just release the emitter.
      log.debug("Streaming chat client disconnected", e);
      emitter.completeWithError(e);
    } catch (RuntimeException e) {
      log.warn("Streaming chat failed", e);
      failStream(emitter, new ApiError("chat_failed", "The chat request could not be completed"));
    }
  }

  private static void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      // Surface as unchecked so the streaming loop unwinds and cancels the upstream generation.
      throw new UncheckedIOException(e);
    }
  }

  private static void failStream(SseEmitter emitter, ApiError error) {
    try {
      emitter.send(SseEmitter.event().name("error").data(error, MediaType.APPLICATION_JSON));
      emitter.complete();
    } catch (Exception sendFailure) {
      // Client already gone; release the emitter with the failure.
      emitter.completeWithError(sendFailure);
    }
  }
}
