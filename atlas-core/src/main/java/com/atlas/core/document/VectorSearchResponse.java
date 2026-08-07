package com.atlas.core.document;

import java.util.List;

/** Vector search response: the ranked hits, most similar first. */
public record VectorSearchResponse(List<VectorSearchHit> results) {}
