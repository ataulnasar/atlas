package com.atlas.core.document;

import com.atlas.core.embedding.VectorLiteral;
import com.atlas.core.ingestion.ChunkCandidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
              .addValue("embedding", VectorLiteral.format(vectors.get(i)));
    }
    jdbcTemplate.batchUpdate(sql, batchParams);
  }

  /**
   * One ranked hit from a search: chunk + its document's filename + the ranker's {@code score}
   * (cosine similarity for vector search, {@code ts_rank_cd} for keyword search). Shared shape so
   * both searches feed the same result mapper.
   */
  record RankedChunkRow(
      UUID chunkId,
      UUID documentId,
      String documentFilename,
      int chunkIndex,
      int startPage,
      int endPage,
      String content,
      double score) {}

  private static final RowMapper<RankedChunkRow> RANKED_CHUNK_ROW_MAPPER =
      (rs, rowNum) ->
          new RankedChunkRow(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("document_id"),
              rs.getString("filename"),
              rs.getInt("chunk_index"),
              rs.getInt("start_page"),
              rs.getInt("end_page"),
              rs.getString("content"),
              rs.getDouble("score"));

  /**
   * Ranks the {@code topK} chunks most similar to {@code queryVector} by cosine distance ({@code
   * <=>}), reporting similarity as {@code 1 - distance}. NULL-embedding chunks are excluded (they
   * aren't searchable), and every hit carries its document's filename for citation. {@code filter}
   * narrows the candidate set in SQL — before ORDER BY / LIMIT — so topK still returns the top
   * matches within the filtered documents rather than filtering an already-truncated result.
   */
  List<RankedChunkRow> searchByVector(float[] queryVector, int topK, SearchFilter filter) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("queryVector", VectorLiteral.format(queryVector))
            .addValue("topK", topK);
    String sql =
        "SELECT c.id, c.document_id, d.filename, c.chunk_index, c.start_page, c.end_page, "
            + "c.content, 1 - (c.embedding <=> CAST(:queryVector AS vector)) AS score "
            + "FROM chunk c JOIN document d ON d.id = c.document_id "
            + "WHERE c.embedding IS NOT NULL"
            + documentFilterClause(params, filter)
            + " ORDER BY c.embedding <=> CAST(:queryVector AS vector) "
            + "LIMIT :topK";
    return jdbcTemplate.query(sql, params, RANKED_CHUNK_ROW_MAPPER);
  }

  /**
   * Ranks the {@code topK} chunks matching {@code query} by full-text relevance. The query is
   * parsed with {@code websearch_to_tsquery}, which treats a natural-language string gracefully
   * (bare words are AND-ed, quotes are phrases, stopwords drop out) rather than erroring; a query
   * that reduces to an empty tsquery — e.g. only stopwords — simply matches nothing. Scoring is
   * {@code ts_rank_cd} against the generated {@code content_tsv} column (backed by its GIN index).
   * No embedding needed, so this works in keyless mode. {@code filter} narrows the candidate set in
   * SQL, before ORDER BY / LIMIT.
   */
  List<RankedChunkRow> searchByKeyword(String query, int topK, SearchFilter filter) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("query", query).addValue("topK", topK);
    String sql =
        "SELECT c.id, c.document_id, d.filename, c.chunk_index, c.start_page, c.end_page, "
            + "c.content, ts_rank_cd(c.content_tsv, q.query) AS score "
            + "FROM chunk c "
            + "JOIN document d ON d.id = c.document_id "
            + "CROSS JOIN websearch_to_tsquery('english', :query) AS q(query) "
            + "WHERE c.content_tsv @@ q.query"
            + documentFilterClause(params, filter)
            + " ORDER BY score DESC "
            + "LIMIT :topK";
    return jdbcTemplate.query(sql, params, RANKED_CHUNK_ROW_MAPPER);
  }

  // Builds the document-filter WHERE fragment and binds its parameters. Both search queries alias
  // chunk as c and document as d, so the fragment is shared. documentIds and filenames both name
  // documents, so when both are given they are OR-ed (union): keep a chunk whose document is named
  // by either list. Within each list it's IN (also OR). An empty filter contributes nothing (and
  // binds nothing) — the search runs over the whole corpus.
  private static String documentFilterClause(MapSqlParameterSource params, SearchFilter filter) {
    List<String> alternatives = new ArrayList<>(2);
    if (!filter.documentIds().isEmpty()) {
      alternatives.add("c.document_id IN (:documentIds)");
      params.addValue("documentIds", filter.documentIds());
    }
    if (!filter.filenames().isEmpty()) {
      alternatives.add("d.filename IN (:filenames)");
      params.addValue("filenames", filter.filenames());
    }
    if (alternatives.isEmpty()) {
      return "";
    }
    return " AND (" + String.join(" OR ", alternatives) + ")";
  }

  int countByDocumentId(UUID documentId) {
    String sql = "SELECT count(*) FROM chunk WHERE document_id = :documentId";
    Integer count =
        jdbcTemplate.queryForObject(
            sql, new MapSqlParameterSource().addValue("documentId", documentId), Integer.class);
    return count != null ? count : 0;
  }

  private static final RowMapper<ChunkView> CHUNK_VIEW_MAPPER =
      (rs, rowNum) ->
          new ChunkView(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("document_id"),
              rs.getString("filename"),
              // No title column in the document table (see V2 schema) — the filename is the title.
              rs.getString("filename"),
              rs.getInt("chunk_index"),
              rs.getInt("start_page"),
              rs.getInt("end_page"),
              rs.getString("content"));

  private static final RowMapper<ChunkBody> CHUNK_BODY_MAPPER =
      (rs, rowNum) ->
          new ChunkBody(
              (UUID) rs.getObject("id"), rs.getString("content"), rs.getInt("token_count"));

  /**
   * The full text and stored token count of the given chunks — what context assembly needs beyond
   * the preview snippet a search hit carries. Order is unspecified (the caller keys by chunkId);
   * unknown ids are simply absent from the result.
   */
  List<ChunkBody> findBodiesByIds(Collection<UUID> chunkIds) {
    if (chunkIds.isEmpty()) {
      return List.of();
    }
    String sql = "SELECT id, content, token_count FROM chunk WHERE id IN (:chunkIds)";
    return jdbcTemplate.query(
        sql, new MapSqlParameterSource().addValue("chunkIds", chunkIds), CHUNK_BODY_MAPPER);
  }

  /**
   * Full detail of a single chunk for the citation-drill-down endpoint; empty if the id is unknown.
   */
  Optional<ChunkView> findChunkView(UUID chunkId) {
    String sql =
        "SELECT c.id, c.document_id, d.filename, c.chunk_index, c.start_page, c.end_page, c.content "
            + "FROM chunk c JOIN document d ON d.id = c.document_id "
            + "WHERE c.id = :chunkId";
    return jdbcTemplate
        .query(sql, new MapSqlParameterSource().addValue("chunkId", chunkId), CHUNK_VIEW_MAPPER)
        .stream()
        .findFirst();
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
