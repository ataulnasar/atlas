package com.atlas.core.chat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ConversationRepository {

  private static final RowMapper<Conversation> ROW_MAPPER =
      (rs, rowNum) ->
          new Conversation(
              (UUID) rs.getObject("id"),
              rs.getObject("created_at", OffsetDateTime.class),
              rs.getObject("updated_at", OffsetDateTime.class));

  private final NamedParameterJdbcTemplate jdbcTemplate;

  ConversationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Starts a new conversation, letting the DB assign the id and timestamps. */
  Conversation create() {
    String sql = "INSERT INTO conversation DEFAULT VALUES RETURNING id, created_at, updated_at";
    return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), ROW_MAPPER);
  }

  /** Bumps {@code updated_at} to now — called when a new turn is appended, for recency ordering. */
  void touch(UUID id) {
    jdbcTemplate.update(
        "UPDATE conversation SET updated_at = now() WHERE id = :id",
        new MapSqlParameterSource().addValue("id", id));
  }

  boolean exists(UUID id) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM conversation WHERE id = :id)",
            new MapSqlParameterSource().addValue("id", id),
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }
}
