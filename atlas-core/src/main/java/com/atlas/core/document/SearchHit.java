package com.atlas.core.document;

import java.util.UUID;

/**
 * One ranked search hit with citation metadata, shared by vector and keyword search. {@code score}
 * is the ranker's score for the hit (cosine similarity for vector search, {@code ts_rank_cd} for
 * keyword search) — higher is more relevant; {@code snippet} is the leading slice of the chunk's
 * content.
 */
public record SearchHit(
    UUID chunkId,
    UUID documentId,
    String documentFilename,
    int chunkIndex,
    int startPage,
    int endPage,
    double score,
    String snippet) {}
