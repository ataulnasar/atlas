package com.atlas.core.document;

import java.util.List;
import java.util.UUID;

/**
 * Optional filter that restricts search to specific documents, by id and/or by filename. Either
 * list, both, or neither may be given; an empty or absent filter means no filtering. When both
 * lists are given they are OR-ed (union) — a chunk is kept if its document is named by
 * <em>either</em> list — since both fields select documents; a caller listing both is just naming a
 * document set two ways rather than intersecting two independent facets.
 *
 * <p>An unknown key inside the filter object is rejected with a 400 (see {@code
 * spring.jackson.deserialization.fail-on-unknown-properties} in application.yml), so a typo'd
 * filter field fails loudly instead of quietly widening the search back to the whole corpus.
 */
public record SearchFilter(List<UUID> documentIds, List<String> filenames) {

  /** The no-op filter — used when a request omits the filter entirely. */
  public static final SearchFilter NONE = new SearchFilter(List.of(), List.of());

  public SearchFilter {
    documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    filenames = filenames == null ? List.of() : List.copyOf(filenames);
  }

  static SearchFilter orNone(SearchFilter filter) {
    return filter == null ? NONE : filter;
  }

  boolean isEmpty() {
    return documentIds.isEmpty() && filenames.isEmpty();
  }
}
