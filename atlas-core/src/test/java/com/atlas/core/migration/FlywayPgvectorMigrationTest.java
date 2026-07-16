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
}
