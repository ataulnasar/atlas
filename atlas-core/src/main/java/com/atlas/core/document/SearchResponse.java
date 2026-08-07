package com.atlas.core.document;

import java.util.List;

/** A search response: the ranked hits, most relevant first. Shared by vector and keyword search. */
public record SearchResponse(List<SearchHit> results) {}
