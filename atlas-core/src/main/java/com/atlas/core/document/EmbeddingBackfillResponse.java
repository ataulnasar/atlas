package com.atlas.core.document;

/** Result of a backfill run: how many chunks were embedded, across how many documents. */
public record EmbeddingBackfillResponse(int chunksEmbedded, int documentsTouched) {}
