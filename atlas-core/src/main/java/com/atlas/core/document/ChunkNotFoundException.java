package com.atlas.core.document;

import java.util.UUID;

public class ChunkNotFoundException extends RuntimeException {

  ChunkNotFoundException(UUID chunkId) {
    super("No chunk found with id: " + chunkId);
  }
}
