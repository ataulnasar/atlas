package com.atlas.core.document;

/**
 * A search request, shared by vector and keyword search. {@code topK} is optional — the controller
 * defaults it to 10 and caps it at 50.
 */
public record SearchRequest(String query, Integer topK) {}
