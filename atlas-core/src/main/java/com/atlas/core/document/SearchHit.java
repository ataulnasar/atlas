package com.atlas.core.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.UUID;

/**
 * One ranked search hit: a {@link Citation} (the cited source) plus the search envelope — {@code
 * chunkIndex} and the ranker's {@code score} (cosine similarity for vector search, {@code
 * ts_rank_cd} for keyword search); higher score is more relevant. Search results and chat citations
 * are the same citation data in different envelopes — this one embeds the citation rather than
 * duplicating its fields.
 *
 * <p>The citation is {@code @JsonUnwrapped}, so the JSON stays flat — {@code citationId}, {@code
 * chunkId}, … appear at the top level alongside {@code chunkIndex} and {@code score}, keeping the
 * search API shape backward-compatible. The delegating accessors are {@code @JsonIgnore}'d so they
 * don't double-serialize; they exist only so callers can read {@code hit.documentFilename()} rather
 * than {@code hit.citation().documentFilename()}.
 */
public record SearchHit(@JsonUnwrapped Citation citation, int chunkIndex, double score) {

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
