package com.atlas.core.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DocumentRepository {

  private static final RowMapper<Document> ROW_MAPPER =
      (rs, rowNum) ->
          new Document(
              (UUID) rs.getObject("id"),
              rs.getString("filename"),
              rs.getString("content_hash"),
              DocumentStatus.valueOf(rs.getString("status")),
              rs.getObject("created_at", OffsetDateTime.class),
              rs.getObject("updated_at", OffsetDateTime.class));

  private final NamedParameterJdbcTemplate jdbcTemplate;

  DocumentRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  Document insertPending(String filename, String contentHash) {
    String sql =
        """
        INSERT INTO document (filename, content_hash, status)
        VALUES (:filename, :contentHash, 'PENDING')
        RETURNING id, filename, content_hash, status, created_at, updated_at
        """;
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("filename", filename)
            .addValue("contentHash", contentHash);
    return jdbcTemplate.queryForObject(sql, params, ROW_MAPPER);
  }

  Optional<Document> findByContentHash(String contentHash) {
    String sql =
        """
        SELECT id, filename, content_hash, status, created_at, updated_at
        FROM document
        WHERE content_hash = :contentHash
        """;
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("contentHash", contentHash);
    List<Document> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
    return results.stream().findFirst();
  }
}
