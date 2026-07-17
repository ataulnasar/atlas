package com.atlas.core.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DocumentRepository {

  private static final RowMapper<Document> ROW_MAPPER =
      (rs, rowNum) ->
          new Document(
              (UUID) rs.getObject("id"),
              rs.getString("filename"),
              rs.getString("content_hash"),
              DocumentStatus.valueOf(rs.getString("status")),
              rs.getString("error_message"),
              rs.getObject("created_at", OffsetDateTime.class),
              rs.getObject("updated_at", OffsetDateTime.class));

  private static final String SELECT_COLUMNS =
      "id, filename, content_hash, status, error_message, created_at, updated_at";

  private final NamedParameterJdbcTemplate jdbcTemplate;

  DocumentRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // NESTED (savepoint-backed): a unique-constraint violation here must only unwind this
  // insert, not poison DocumentUploadService's whole transaction — Postgres aborts the entire
  // transaction on any statement error, which would otherwise take the caller's subsequent
  // findByContentHash lookup down with it.
  @Transactional(propagation = Propagation.NESTED)
  Document insertPending(String filename, String contentHash) {
    String sql =
        "INSERT INTO document (filename, content_hash, status) "
            + "VALUES (:filename, :contentHash, 'PENDING') "
            + "RETURNING "
            + SELECT_COLUMNS;
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("filename", filename)
            .addValue("contentHash", contentHash);
    return jdbcTemplate.queryForObject(sql, params, ROW_MAPPER);
  }

  Optional<Document> findByContentHash(String contentHash) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM document WHERE content_hash = :contentHash";
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("contentHash", contentHash);
    List<Document> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
    return results.stream().findFirst();
  }

  Optional<Document> findById(UUID id) {
    String sql = "SELECT " + SELECT_COLUMNS + " FROM document WHERE id = :id";
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
    List<Document> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
    return results.stream().findFirst();
  }

  /**
   * Atomically claims the document for processing: only succeeds if it's still PENDING. This is the
   * guard against double-processing (e.g. a redelivered event) — returns {@code false} if another
   * invocation already claimed it, or it was never PENDING.
   */
  boolean claimForProcessing(UUID id) {
    String sql =
        "UPDATE document SET status = 'PROCESSING', updated_at = now() "
            + "WHERE id = :id AND status = 'PENDING'";
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
    return jdbcTemplate.update(sql, params) == 1;
  }

  /**
   * Resets a FAILED document back to PENDING so a re-upload of identical bytes can retry it under
   * the same id instead of being rejected forever by the content_hash unique index. Guarded by
   * {@code status = 'FAILED'} in the WHERE clause: if two concurrent retries race for the same row,
   * only the one that wins the row lock first sees its update land — the loser gets {@code false}
   * and must fall back to the ordinary duplicate rejection rather than double-firing the retry.
   */
  boolean resetFailedToPending(UUID id) {
    String sql =
        "UPDATE document SET status = 'PENDING', error_message = NULL, updated_at = now() "
            + "WHERE id = :id AND status = 'FAILED'";
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
    return jdbcTemplate.update(sql, params) == 1;
  }

  void markReady(UUID id) {
    String sql = "UPDATE document SET status = 'READY', updated_at = now() WHERE id = :id";
    jdbcTemplate.update(sql, new MapSqlParameterSource().addValue("id", id));
  }

  void markFailed(UUID id, String errorMessage) {
    String sql =
        "UPDATE document SET status = 'FAILED', error_message = :errorMessage, "
            + "updated_at = now() WHERE id = :id";
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("id", id).addValue("errorMessage", errorMessage);
    jdbcTemplate.update(sql, params);
  }
}
