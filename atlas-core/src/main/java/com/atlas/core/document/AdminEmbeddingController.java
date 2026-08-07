package com.atlas.core.document;

import com.atlas.core.document.ChunkEmbeddingService.BackfillResult;
import com.atlas.core.embedding.EmbeddingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(security): unauthenticated for now — like every /api/** endpoint, this admin operation must
// require the X-API-Key header once "Add API key authentication" (docs/plan.md, Phase 3) lands.
// A backfill is expensive and provider-billed, so it especially shouldn't stay open.
@RestController
@RequestMapping("/api/admin/embeddings")
class AdminEmbeddingController {

  private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
  private final ChunkEmbeddingService chunkEmbeddingService;

  AdminEmbeddingController(
      ObjectProvider<EmbeddingService> embeddingServiceProvider,
      ChunkEmbeddingService chunkEmbeddingService) {
    this.embeddingServiceProvider = embeddingServiceProvider;
    this.chunkEmbeddingService = chunkEmbeddingService;
  }

  @PostMapping("/backfill")
  ResponseEntity<Object> backfill() {
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    if (embeddingService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              new ApiError(
                  "embedding_disabled",
                  "No embedding provider is configured (missing API key); backfill is unavailable"));
    }

    BackfillResult result = chunkEmbeddingService.backfill(embeddingService);
    return ResponseEntity.ok(
        new EmbeddingBackfillResponse(result.chunksEmbedded(), result.documentsTouched()));
  }
}
