package com.atlas.core.document;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Fetches full chunk bodies (content + token count) by id, keyed for lookup. The public seam the
 * chat/RAG path uses to turn retrieval hits into assemblable context, without exposing the
 * package-private {@link ChunkRepository}.
 */
@Service
public class ChunkContentService {

  private final ChunkRepository chunkRepository;

  ChunkContentService(ChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  /** The bodies of the given chunks, keyed by chunk id; unknown ids are absent from the map. */
  public Map<UUID, ChunkBody> findBodies(Collection<UUID> chunkIds) {
    Map<UUID, ChunkBody> byId = new HashMap<>();
    for (ChunkBody body : chunkRepository.findBodiesByIds(chunkIds)) {
      byId.put(body.chunkId(), body);
    }
    return byId;
  }
}
