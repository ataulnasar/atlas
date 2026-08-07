package com.atlas.core.document;

import java.util.UUID;

/**
 * A hybrid search hit: the same citation shape as {@link SearchHit}, but {@code score} is the fused
 * Reciprocal Rank Fusion score, plus three transparency fields showing how the hit was found —
 * {@code foundBy} ({@code "vector"}, {@code "keyword"}, or {@code "both"}) and the per-source ranks
 * ({@code vectorRank}/{@code keywordRank}, each null when the hit didn't appear in that source).
 */
public record HybridSearchHit(
    UUID chunkId,
    UUID documentId,
    String documentFilename,
    int chunkIndex,
    int startPage,
    int endPage,
    double score,
    String snippet,
    String foundBy,
    Integer vectorRank,
    Integer keywordRank) {}
