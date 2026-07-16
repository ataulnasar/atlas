package com.atlas.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test for the default profile's logging pipeline: confirms the root console appender is
 * JSON-encoded (Logstash) and that a real log line, with a correlation ID in MDC, comes out as
 * valid, parseable JSON.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class StructuredJsonLoggingSmokeTest {

  @Test
  void defaultProfileEmitsValidJsonLogLinesWithCorrelationId() throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    ConsoleAppender<ILoggingEvent> consoleAppender = findConsoleAppender(root);

    assertThat(consoleAppender.getEncoder().getClass().getName()).containsIgnoringCase("logstash");

    ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
    consoleAppender.setOutputStream(new PrintStream(capturedOutput, true, StandardCharsets.UTF_8));

    Logger testLogger = context.getLogger(StructuredJsonLoggingSmokeTest.class);
    MDC.put(CorrelationIdFilter.MDC_KEY, "smoke-test-correlation-id");
    try {
      testLogger.info("structured logging smoke test");
    } finally {
      MDC.remove(CorrelationIdFilter.MDC_KEY);
      consoleAppender.setOutputStream(System.out);
    }

    String[] lines = capturedOutput.toString(StandardCharsets.UTF_8).trim().split("\\R");
    assertThat(lines).isNotEmpty();

    JsonNode logLine = new ObjectMapper().readTree(lines[lines.length - 1]);

    assertThat(logLine.path("message").asText()).isEqualTo("structured logging smoke test");
    assertThat(logLine.path("correlationId").asText()).isEqualTo("smoke-test-correlation-id");
  }

  @SuppressWarnings("unchecked")
  private ConsoleAppender<ILoggingEvent> findConsoleAppender(Logger root) {
    Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
    while (appenders.hasNext()) {
      Appender<ILoggingEvent> appender = appenders.next();
      if (appender instanceof ConsoleAppender) {
        return (ConsoleAppender<ILoggingEvent>) appender;
      }
    }
    throw new IllegalStateException("No console appender configured on root logger");
  }
}
