package com.atlas.core.document;

/**
 * Read-only corpus counts for diagnostics (consumed by {@code atlas-eval doctor}). Counts only — no
 * document contents or filenames — so it is safe to expose behind the API key.
 */
public record AdminStatsResponse(
    long totalDocuments, long readyDocuments, long totalChunks, long chunksWithoutEmbedding) {}
