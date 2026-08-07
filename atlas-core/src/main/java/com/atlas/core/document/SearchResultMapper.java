package com.atlas.core.document;

import com.atlas.core.document.ChunkRepository.RankedChunkRow;
import java.util.List;

/**
 * Shapes a ranked chunk row into a citation-carrying {@link SearchHit}: a leading-content snippet
 * and a score rounded for presentation. Shared by vector and keyword search so both endpoints
 * return byte-for-byte the same hit shape from the same code.
 */
final class SearchResultMapper {

  private static final int SNIPPET_MAX_CHARS = 300;
  private static final double SCORE_ROUNDING = 10_000d; // 4 decimal places

  private SearchResultMapper() {}

  static List<SearchHit> toHits(List<RankedChunkRow> rows) {
    return rows.stream().map(SearchResultMapper::toHit).toList();
  }

  private static SearchHit toHit(RankedChunkRow row) {
    String content = row.content();
    String snippet =
        content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS);
    double score = Math.round(row.score() * SCORE_ROUNDING) / SCORE_ROUNDING;
    return new SearchHit(
        row.chunkId(),
        row.documentId(),
        row.documentFilename(),
        row.chunkIndex(),
        row.startPage(),
        row.endPage(),
        score,
        snippet);
  }
}
