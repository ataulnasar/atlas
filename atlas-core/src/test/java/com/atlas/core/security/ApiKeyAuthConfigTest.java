package com.atlas.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The startup posture log and filter registration. The WARN is emitted at bean-creation (startup),
 * not per request, so a single construction produces exactly one WARN — that's what "logged once"
 * means here.
 */
class ApiKeyAuthConfigTest {

  private final ApiKeyAuthConfig config = new ApiKeyAuthConfig();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(ApiKeyAuthConfig.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  @Test
  void disabledModeLogsExactlyOneStartupWarn() {
    config.apiKeyAuthFilter("", objectMapper);

    long warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    assertThat(warnings).isEqualTo(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("API authentication disabled")
        .contains("ATLAS_API_KEY");
  }

  @Test
  void enabledModeLogsNoWarn() {
    config.apiKeyAuthFilter("a-configured-key", objectMapper);

    assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.INFO);
  }

  @Test
  void filterIsRegisteredForApiRoutesOnly() {
    var registration = config.apiKeyAuthFilter("k", objectMapper);
    assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
  }
}
