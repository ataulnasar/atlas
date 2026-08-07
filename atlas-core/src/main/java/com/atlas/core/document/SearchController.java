package com.atlas.core.document;

import com.atlas.core.embedding.EmbeddingService;
import java.util.List;
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
@RequestMapping("/api/search")
class SearchController {

  private static final int DEFAULT_TOP_K = 10;
  private static final int MAX_TOP_K = 50;

  private final ObjectProvider<EmbeddingService> embeddingServiceProvider;
  private final VectorSearchService vectorSearchService;
  private final KeywordSearchService keywordSearchService;
  private final HybridSearchService hybridSearchService;

  SearchController(
      ObjectProvider<EmbeddingService> embeddingServiceProvider,
      VectorSearchService vectorSearchService,
      KeywordSearchService keywordSearchService,
      HybridSearchService hybridSearchService) {
    this.embeddingServiceProvider = embeddingServiceProvider;
    this.vectorSearchService = vectorSearchService;
    this.keywordSearchService = keywordSearchService;
    this.hybridSearchService = hybridSearchService;
  }

  @PostMapping("/vector")
  ResponseEntity<Object> vectorSearch(@RequestBody(required = false) SearchRequest request) {
    ResponseEntity<Object> invalid = validateQuery(request);
    if (invalid != null) {
      return invalid;
    }

    // Vector search needs the query embedded, so it's unavailable in keyless mode — same contract
    // as the backfill endpoint.
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    if (embeddingService == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              new ApiError(
                  "embedding_disabled",
                  "No embedding provider is configured (missing API key); vector search is unavailable"));
    }

    int topK = normalizeTopK(request.topK());
    List<SearchHit> hits =
        vectorSearchService.search(request.query().strip(), topK, embeddingService);
    return ResponseEntity.ok(new SearchResponse(hits));
  }

  @PostMapping("/keyword")
  ResponseEntity<Object> keywordSearch(@RequestBody(required = false) SearchRequest request) {
    ResponseEntity<Object> invalid = validateQuery(request);
    if (invalid != null) {
      return invalid;
    }

    // No embedding needed — keyword search is the degraded-mode search and works without a
    // provider.
    int topK = normalizeTopK(request.topK());
    List<SearchHit> hits = keywordSearchService.search(request.query().strip(), topK);
    return ResponseEntity.ok(new SearchResponse(hits));
  }

  @PostMapping("/hybrid")
  ResponseEntity<Object> hybridSearch(@RequestBody(required = false) SearchRequest request) {
    ResponseEntity<Object> invalid = validateQuery(request);
    if (invalid != null) {
      return invalid;
    }

    // Never 503: hybrid is the endpoint a UI can always call. A missing provider degrades it to
    // keyword-only inside the service rather than failing here.
    EmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
    int topK = normalizeTopK(request.topK());
    List<HybridSearchHit> hits =
        hybridSearchService.search(request.query().strip(), topK, embeddingService);
    return ResponseEntity.ok(new HybridSearchResponse(hits));
  }

  private static ResponseEntity<Object> validateQuery(SearchRequest request) {
    if (request == null || request.query() == null || request.query().isBlank()) {
      return ResponseEntity.badRequest()
          .body(new ApiError("invalid_query", "query must not be blank"));
    }
    return null;
  }

  private static int normalizeTopK(Integer requested) {
    if (requested == null) {
      return DEFAULT_TOP_K;
    }
    return Math.max(1, Math.min(requested, MAX_TOP_K));
  }
}
