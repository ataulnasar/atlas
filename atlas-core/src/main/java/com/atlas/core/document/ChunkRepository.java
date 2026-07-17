package com.atlas.core.document;

import com.atlas.core.ingestion.ChunkCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ChunkRepository {

  private final NamedParameterJdbcTemplate jdbcTemplate;

  ChunkRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Atomic: either every chunk for this document lands, or none does. A partial batch would
  // leave a misleading fragment of the document searchable while the row is later marked FAILED.
  @Transactional
  void insertAll(UUID documentId, List<ChunkCandidate> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    String sql =
        "INSERT INTO chunk (document_id, chunk_index, content, start_page, end_page, token_count) "
            + "VALUES (:documentId, :chunkIndex, :content, :startPage, :endPage, :tokenCount)";
    MapSqlParameterSource[] batchParams =
        chunks.stream()
            .map(chunk -> toParams(documentId, chunk))
            .toArray(MapSqlParameterSource[]::new);
    jdbcTemplate.batchUpdate(sql, batchParams);
  }

  /** Clears a document's chunks before a retry re-ingests it from scratch. */
  void deleteByDocumentId(UUID documentId) {
    String sql = "DELETE FROM chunk WHERE document_id = :documentId";
    jdbcTemplate.update(sql, new MapSqlParameterSource().addValue("documentId", documentId));
  }

  int countByDocumentId(UUID documentId) {
    String sql = "SELECT count(*) FROM chunk WHERE document_id = :documentId";
    Integer count =
        jdbcTemplate.queryForObject(
            sql, new MapSqlParameterSource().addValue("documentId", documentId), Integer.class);
    return count != null ? count : 0;
  }

  private MapSqlParameterSource toParams(UUID documentId, ChunkCandidate chunk) {
    return new MapSqlParameterSource()
        .addValue("documentId", documentId)
        .addValue("chunkIndex", chunk.chunkIndex())
        .addValue("content", chunk.content())
        .addValue("startPage", chunk.startPage())
        .addValue("endPage", chunk.endPage())
        .addValue("tokenCount", chunk.tokenCount());
  }
}
