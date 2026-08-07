package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.RankedChunkRow;
import java.util.ArrayList;
import java.util.List;

/**
 * Shapes ranked chunk rows into citation-carrying {@link SearchHit}s: builds each row's {@link
 * Citation} (a leading-content snippet plus a per-response citationId — c1, c2, … by rank) and
 * wraps it with the search envelope (chunkIndex and a rounded score). Shared by vector and keyword
 * search so both endpoints return byte-for-byte the same hit shape from the same code.
 */
final class SearchResultMapper {

  private static final int SNIPPET_MAX_CHARS = 300;
  private static final double SCORE_ROUNDING = 10_000d; // 4 decimal places

  private SearchResultMapper() {}

  static List<SearchHit> toHits(List<RankedChunkRow> rows) {
    List<SearchHit> hits = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      RankedChunkRow row = rows.get(i);
      Citation citation = citationOf(citationId(i + 1), row);
      double score = round(row.score());
      hits.add(new SearchHit(citation, row.chunkIndex(), score));
    }
    return hits;
  }

  /** Builds a {@link Citation} for a chunk row, labelled with {@code citationId}. */
  static Citation citationOf(String citationId, RankedChunkRow row) {
    return new Citation(
        citationId,
        row.chunkId(),
        row.documentId(),
        row.documentFilename(),
        // No title column in the document table (see V2 schema) — the filename is the title.
        row.documentFilename(),
        row.startPage(),
        row.endPage(),
        snippetOf(row.content()));
  }

  static String citationId(int rank) {
    return "c" + rank;
  }

  static double round(double score) {
    return Math.round(score * SCORE_ROUNDING) / SCORE_ROUNDING;
  }

  private static String snippetOf(String content) {
    return content.length() <= SNIPPET_MAX_CHARS
        ? content
        : content.substring(0, SNIPPET_MAX_CHARS);
  }
}
