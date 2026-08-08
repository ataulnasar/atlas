package com.atlas.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the real Spring context (Flyway runs on startup) against a disposable
 * pgvector/pgvector:pg16 Testcontainer — independent of the docker-compose stack.
 */
@SpringBootTest
@Testcontainers
class FlywayPgvectorMigrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flywayAppliesV1AndEnablesThePgvectorExtension() {
    Integer appliedV1Migrations =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success = true",
            Integer.class);
    assertThat(appliedV1Migrations).isEqualTo(1);

    Integer vectorExtensionCount =
        jdbcTemplate.queryForObject(
            "select count(*) from pg_extension where extname = 'vector'", Integer.class);
    assertThat(vectorExtensionCount).isEqualTo(1);
  }

  @Test
  void flywayAppliesV2AndCreatesDocumentAndChunkSchemaWithIndexes() {
    Integer appliedV2Migrations =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '2' and success = true",
            Integer.class);
    assertThat(appliedV2Migrations).isEqualTo(1);

    assertThat(jdbcTemplate.queryForObject("select to_regclass('document')", String.class))
        .isEqualTo("document");
    assertThat(jdbcTemplate.queryForObject("select to_regclass('chunk')", String.class))
        .isEqualTo("chunk");

    String hnswIndexDef =
        jdbcTemplate.queryForObject(
            "select indexdef from pg_indexes where indexname = 'idx_chunk_embedding_hnsw'",
            String.class);
    assertThat(hnswIndexDef).containsIgnoringCase("using hnsw");

    String ginIndexDef =
        jdbcTemplate.queryForObject(
            "select indexdef from pg_indexes where indexname = 'idx_chunk_content_tsv_gin'",
            String.class);
    assertThat(ginIndexDef).containsIgnoringCase("using gin");
  }

  @Test
  void flywayAppliesV3AndAddsChunkPageProvenanceColumns() {
    Integer appliedV3Migrations =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '3' and success = true",
            Integer.class);
    assertThat(appliedV3Migrations).isEqualTo(1);

    Integer startPageNotNull =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_name = 'chunk' and column_name = 'start_page' "
                + "and is_nullable = 'NO'",
            Integer.class);
    assertThat(startPageNotNull).isEqualTo(1);

    Integer endPageNotNull =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_name = 'chunk' and column_name = 'end_page' "
                + "and is_nullable = 'NO'",
            Integer.class);
    assertThat(endPageNotNull).isEqualTo(1);
  }

  @Test
  void flywayAppliesV4AndAddsErrorMessageAndTokenCountColumns() {
    Integer appliedV4Migrations =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '4' and success = true",
            Integer.class);
    assertThat(appliedV4Migrations).isEqualTo(1);

    Integer errorMessageNullable =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_name = 'document' and column_name = 'error_message' "
                + "and is_nullable = 'YES'",
            Integer.class);
    assertThat(errorMessageNullable).isEqualTo(1);

    Integer tokenCountNotNull =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_name = 'chunk' and column_name = 'token_count' "
                + "and is_nullable = 'NO'",
            Integer.class);
    assertThat(tokenCountNotNull).isEqualTo(1);
  }

  @Test
  void flywayAppliesV5AndCreatesConversationAndMessageSchema() {
    Integer appliedV5Migrations =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '5' and success = true",
            Integer.class);
    assertThat(appliedV5Migrations).isEqualTo(1);

    assertThat(jdbcTemplate.queryForObject("select to_regclass('conversation')", String.class))
        .isEqualTo("conversation");
    assertThat(jdbcTemplate.queryForObject("select to_regclass('message')", String.class))
        .isEqualTo("message");

    // citations is JSONB and nullable (NULL for user turns).
    Integer citationsJsonbNullable =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_name = 'message' and column_name = 'citations' "
                + "and data_type = 'jsonb' and is_nullable = 'YES'",
            Integer.class);
    assertThat(citationsJsonbNullable).isEqualTo(1);

    // Stable ordering is enforced by a UNIQUE (conversation_id, seq) constraint.
    Integer messageUniqueConstraints =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.table_constraints "
                + "where table_name = 'message' and constraint_type = 'UNIQUE'",
            Integer.class);
    assertThat(messageUniqueConstraints).isEqualTo(1);

    // message.conversation_id cascades on delete.
    String deleteRule =
        jdbcTemplate.queryForObject(
            "select rc.delete_rule from information_schema.referential_constraints rc "
                + "join information_schema.table_constraints tc "
                + "  on rc.constraint_name = tc.constraint_name "
                + "where tc.table_name = 'message' and tc.constraint_type = 'FOREIGN KEY'",
            String.class);
    assertThat(deleteRule).isEqualTo("CASCADE");
  }
}
