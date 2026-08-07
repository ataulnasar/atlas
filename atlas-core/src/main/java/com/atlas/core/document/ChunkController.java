package com.atlas.core.document;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(security): unauthenticated for now — like every /api/** endpoint, this must require the
// X-API-Key header once "Add API key authentication" (docs/plan.md, Phase 3) lands.
@RestController
@RequestMapping("/api/chunks")
class ChunkController {

  private final ChunkRepository chunkRepository;

  ChunkController(ChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  /**
   * Returns a chunk's full content and citation metadata — the "click a citation, see the source"
   * lookup the UI makes with a chunkId from a search hit or an answer's citation. 404 if unknown.
   */
  @GetMapping("/{id}")
  ResponseEntity<ChunkView> getChunk(@PathVariable UUID id) {
    ChunkView chunk =
        chunkRepository.findChunkView(id).orElseThrow(() -> new ChunkNotFoundException(id));
    return ResponseEntity.ok(chunk);
  }
}
