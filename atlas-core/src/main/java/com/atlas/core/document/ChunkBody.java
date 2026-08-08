package com.atlas.core.document;

import java.util.UUID;

/**
 * A chunk's full {@code content} and stored {@code tokenCount} (the {@code chunk.token_count}
 * column) — the two things context assembly needs that a search hit's {@link Citation} doesn't
 * carry (its snippet is a clipped preview, and it has no token count). Fetched by chunk id for the
 * hits retrieval returned.
 */
public record ChunkBody(UUID chunkId, String content, int tokenCount) {}
