package com.atlas.core.document;

import java.util.List;

/** A hybrid search response: the fused hits, highest RRF score first. */
public record HybridSearchResponse(List<HybridSearchHit> results) {}
