package com.atlas.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Every response carries an {@value CorrelationIdFilter#CORRELATION_ID_HEADER} header,
 * even for an unmapped path — the filter runs ahead of request dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class CorrelationIdHeaderIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void everyResponseCarriesACorrelationIdHeader() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getHeaders().get(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNotEmpty();
    }
}
