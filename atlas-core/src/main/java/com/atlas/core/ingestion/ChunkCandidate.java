package com.atlas.core.ingestion;

/**
 * A chunk produced by {@link ChunkingService}, not yet persisted. Persistence (and the resulting
 * {@code chunk} table row) is the async ingestion processor's job — this is pure text-to-chunks.
 */
public record ChunkCandidate(
    int chunkIndex, String content, int startPage, int endPage, int tokenCount) {}
