package com.atlas.core.document;

import com.atlas.core.ingestion.ChunkCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
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

  /** A chunk's id and content — the minimum needed to embed it and write the vector back. */
  record ChunkToEmbed(UUID id, UUID documentId, String content) {}

  private static final RowMapper<ChunkToEmbed> CHUNK_TO_EMBED_MAPPER =
      (rs, rowNum) ->
          new ChunkToEmbed(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("document_id"),
              rs.getString("content"));

  /** A chunk's not-yet-embedded rows, in chunk order, for a single document. */
  List<ChunkToEmbed> findChunksWithoutEmbedding(UUID documentId) {
    String sql =
        "SELECT id, document_id, content FROM chunk "
            + "WHERE document_id = :documentId AND embedding IS NULL "
            + "ORDER BY chunk_index";
    return jdbcTemplate.query(
        sql, new MapSqlParameterSource().addValue("documentId", documentId), CHUNK_TO_EMBED_MAPPER);
  }

  /**
   * The next batch of not-yet-embedded chunks belonging to READY documents, capped at {@code
   * limit}. Only READY documents are eligible — PENDING/PROCESSING ones are mid-ingestion and get
   * their embeddings on the ingestion path; FAILED ones aren't searchable. Ordering is stable so
   * repeated calls make forward progress as rows are filled in.
   */
  List<ChunkToEmbed> findNextChunksWithoutEmbeddingForReadyDocuments(int limit) {
    String sql =
        "SELECT c.id, c.document_id, c.content FROM chunk c "
            + "JOIN document d ON d.id = c.document_id "
            + "WHERE c.embedding IS NULL AND d.status = 'READY' "
            + "ORDER BY c.document_id, c.chunk_index "
            + "LIMIT :limit";
    return jdbcTemplate.query(
        sql, new MapSqlParameterSource().addValue("limit", limit), CHUNK_TO_EMBED_MAPPER);
  }

  /**
   * Writes one batch of embeddings atomically. The vector is passed as pgvector's text form and
   * cast server-side, so no pgvector-specific JDBC type is needed. Either the whole batch lands or
   * none of it does — a half-written batch would leave chunks the caller believes it embedded still
   * NULL.
   */
  @Transactional
  void updateEmbeddings(List<UUID> chunkIds, List<float[]> vectors) {
    if (chunkIds.size() != vectors.size()) {
      throw new IllegalArgumentException(
          "chunkIds and vectors must be the same length: "
              + chunkIds.size()
              + " vs "
              + vectors.size());
    }
    if (chunkIds.isEmpty()) {
      return;
    }
    String sql =
        "UPDATE chunk SET embedding = CAST(:embedding AS vector), updated_at = now() "
            + "WHERE id = :id";
    MapSqlParameterSource[] batchParams = new MapSqlParameterSource[chunkIds.size()];
    for (int i = 0; i < chunkIds.size(); i++) {
      batchParams[i] =
          new MapSqlParameterSource()
              .addValue("id", chunkIds.get(i))
              .addValue("embedding", toVectorLiteral(vectors.get(i)));
    }
    jdbcTemplate.batchUpdate(sql, batchParams);
  }

  // pgvector accepts a bracketed, comma-separated text literal, e.g. "[0.1,0.2,0.3]".
  private static String toVectorLiteral(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 12 + 2);
    builder.append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(vector[i]);
    }
    builder.append(']');
    return builder.toString();
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
