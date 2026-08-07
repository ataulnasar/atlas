package com.atlas.core.document;

/**
 * A search request, shared by vector, keyword, and hybrid search. {@code topK} is optional — the
 * controller defaults it to 10 and caps it at 50. {@code filter} is optional — absent (or empty)
 * means search the whole corpus.
 */
public record SearchRequest(String query, Integer topK, SearchFilter filter) {

  /** Convenience for callers (and tests) that don't filter. */
  public SearchRequest(String query, Integer topK) {
    this(query, topK, null);
  }
}
