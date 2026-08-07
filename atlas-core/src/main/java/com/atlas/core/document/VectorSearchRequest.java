package com.atlas.core.document;

/**
 * Vector search request. {@code topK} is optional — the controller defaults it to 10 and caps it at
 * 50.
 */
public record VectorSearchRequest(String query, Integer topK) {}
