package com.atlas.core.document;

import java.util.UUID;

/**
 * The full detail of one chunk, returned by {@code GET /api/chunks/{id}} — the "click a citation,
 * see the source" endpoint. Carries the same citation metadata a {@link Citation} does (which
 * document, which pages), but the whole {@code content} instead of a snippet, and no per-response
 * {@code citationId} (a single chunk fetch isn't part of an answer's citation list).
 */
public record ChunkView(
    UUID chunkId,
    UUID documentId,
    String documentFilename,
    String documentTitle,
    int chunkIndex,
    int startPage,
    int endPage,
    String content) {}
