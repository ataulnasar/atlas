package com.atlas.core.document;

import java.util.UUID;

/**
 * One ranked search hit with citation metadata. {@code score} is cosine similarity ({@code 1 -
 * distance}), where 1.0 is identical; {@code snippet} is the leading slice of the chunk's content.
 */
public record VectorSearchHit(
    UUID chunkId,
    UUID documentId,
    String documentFilename,
    int chunkIndex,
    int startPage,
    int endPage,
    double score,
    String snippet) {}
