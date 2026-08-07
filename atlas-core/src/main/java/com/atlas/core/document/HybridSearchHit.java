package com.atlas.core.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.UUID;

/**
 * A hybrid search hit: the same embedded {@link Citation} as {@link SearchHit}, but {@code score}
 * is the fused Reciprocal Rank Fusion score, plus three transparency fields showing how the hit was
 * found — {@code foundBy} ({@code "vector"}, {@code "keyword"}, or {@code "both"}) and the
 * per-source ranks ({@code vectorRank}/{@code keywordRank}, each null when the hit didn't appear in
 * that source). The citation is {@code @JsonUnwrapped} so the JSON stays flat; delegating accessors
 * are {@code @JsonIgnore}'d, as in {@link SearchHit}.
 */
public record HybridSearchHit(
    @JsonUnwrapped Citation citation,
    int chunkIndex,
    double score,
    String foundBy,
    Integer vectorRank,
    Integer keywordRank) {

  @JsonIgnore
  public String citationId() {
    return citation.citationId();
  }

  @JsonIgnore
  public UUID chunkId() {
    return citation.chunkId();
  }

  @JsonIgnore
  public UUID documentId() {
    return citation.documentId();
  }

  @JsonIgnore
  public String documentFilename() {
    return citation.documentFilename();
  }

  @JsonIgnore
  public String documentTitle() {
    return citation.documentTitle();
  }

  @JsonIgnore
  public int startPage() {
    return citation.startPage();
  }

  @JsonIgnore
  public int endPage() {
    return citation.endPage();
  }

  @JsonIgnore
  public String snippet() {
    return citation.snippet();
  }
}
