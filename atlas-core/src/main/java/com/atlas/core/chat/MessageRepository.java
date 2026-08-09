package com.atlas.core.chat;

import com.atlas.core.document.Citation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MessageRepository {

  private static final TypeReference<List<Citation>> CITATION_LIST = new TypeReference<>() {};

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  MessageRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Appends a message with the next {@code seq} for the conversation, computed and inserted in one
   * statement so the read-and-increment can't interleave within a transaction. {@code citations} is
   * the assistant turn's source list (stored as JSONB) or {@code null} for a user turn.
   *
   * <p>Concurrent appends to the same conversation could still both compute the same next seq; the
   * {@code UNIQUE (conversation_id, seq)} constraint turns that into a failed insert rather than a
   * silently duplicated seq. Turns within a single conversation are appended sequentially, so this
   * is a guardrail, not a hot path.
   */
  @Transactional
  ChatMessage append(
      UUID conversationId, MessageRole role, String content, List<Citation> citations) {
    String sql =
        "INSERT INTO message (conversation_id, seq, role, content, citations) "
            + "SELECT :conversationId, COALESCE(MAX(seq), 0) + 1, :role, :content, "
            + "CAST(:citations AS jsonb) "
            + "FROM message WHERE conversation_id = :conversationId "
            + "RETURNING id, conversation_id, seq, role, content, citations, created_at";
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("conversationId", conversationId)
            .addValue("role", role.dbValue())
            .addValue("content", content)
            .addValue("citations", toJson(citations));
    return jdbcTemplate.queryForObject(sql, params, this::mapRow);
  }

  /** The most recent {@code limit} messages of a conversation, returned in {@code seq} order. */
  List<ChatMessage> lastMessages(UUID conversationId, int limit) {
    String sql =
        "SELECT id, conversation_id, seq, role, content, citations, created_at FROM ("
            + "  SELECT id, conversation_id, seq, role, content, citations, created_at "
            + "  FROM message WHERE conversation_id = :conversationId "
            + "  ORDER BY seq DESC LIMIT :limit"
            + ") recent ORDER BY seq ASC";
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("conversationId", conversationId)
            .addValue("limit", limit);
    return jdbcTemplate.query(sql, params, this::mapRow);
  }

  /** Every message of a conversation, oldest first ({@code seq} ascending) — the read-back path. */
  List<ChatMessage> findByConversationId(UUID conversationId) {
    String sql =
        "SELECT id, conversation_id, seq, role, content, citations, created_at "
            + "FROM message WHERE conversation_id = :conversationId ORDER BY seq ASC";
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("conversationId", conversationId);
    return jdbcTemplate.query(sql, params, this::mapRow);
  }

  private ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    String citationsJson = rs.getString("citations");
    return new ChatMessage(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("conversation_id"),
        rs.getInt("seq"),
        MessageRole.fromDbValue(rs.getString("role")),
        rs.getString("content"),
        citationsJson == null ? null : fromJson(citationsJson),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private String toJson(List<Citation> citations) {
    if (citations == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(citations);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize citations to JSON", e);
    }
  }

  private List<Citation> fromJson(String json) {
    try {
      return objectMapper.readValue(json, CITATION_LIST);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize citations from JSON", e);
    }
  }
}
