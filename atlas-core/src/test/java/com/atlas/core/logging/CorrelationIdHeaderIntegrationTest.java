package com.atlas.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Every response carries an {@value CorrelationIdFilter#CORRELATION_ID_HEADER} header, even for an
 * unmapped path — the filter runs ahead of request dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CorrelationIdHeaderIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void everyResponseCarriesACorrelationIdHeader() {
    ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

    assertThat(response.getHeaders().get(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNotEmpty();
  }
}
