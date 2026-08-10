package com.atlas.core.document;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corpus-state counts for operational diagnostics — the source for {@code atlas-eval doctor}'s
 * corpus check (document count, chunk count, and how many chunks are still awaiting an embedding).
 *
 * <p>Lives under {@code /api/**}, so it is guarded by the {@code X-API-Key} filter automatically
 * (see {@code ApiKeyAuthConfig}). It returns aggregate counts only — never document contents — so
 * exposing it carries no data-leak risk beyond what the counts themselves reveal.
 */
@RestController
@RequestMapping("/api/admin/stats")
class AdminStatsController {

  private final DocumentRepository documentRepository;
  private final ChunkRepository chunkRepository;

  AdminStatsController(DocumentRepository documentRepository, ChunkRepository chunkRepository) {
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
  }

  @GetMapping
  ResponseEntity<AdminStatsResponse> stats() {
    return ResponseEntity.ok(
        new AdminStatsResponse(
            documentRepository.count(),
            documentRepository.countByStatus(DocumentStatus.READY),
            chunkRepository.count(),
            chunkRepository.countWithoutEmbedding()));
  }
}
