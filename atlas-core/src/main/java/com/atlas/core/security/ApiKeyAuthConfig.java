package com.atlas.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link ApiKeyAuthFilter} for {@code /api/*} only (so {@code /actuator/health} and any
 * non-API path stay open), and logs the effective auth posture once at startup.
 *
 * <p>The expected key comes strictly from the {@code ATLAS_API_KEY} environment variable — never a
 * default in {@code application.yml}, the same discipline as the OpenAI key. Blank/unset means auth
 * is disabled for local dev, CI, and the quickstart; that is surfaced by a prominent WARN so the
 * production posture is explicit rather than an accidental open door.
 */
@Configuration
public class ApiKeyAuthConfig {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthConfig.class);

  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
      @Value("${ATLAS_API_KEY:}") String apiKey, ObjectMapper objectMapper) {
    boolean enabled = apiKey != null && !apiKey.isBlank();
    if (enabled) {
      log.info("API authentication enabled — X-API-Key required on /api/**.");
    } else {
      log.warn(
          "API authentication disabled — no ATLAS_API_KEY configured. All /api/** endpoints are"
              + " open; set ATLAS_API_KEY to require an X-API-Key header (production posture).");
    }

    FilterRegistrationBean<ApiKeyAuthFilter> registration =
        new FilterRegistrationBean<>(new ApiKeyAuthFilter(apiKey, objectMapper));
    registration.addUrlPatterns("/api/*");
    registration.setName("apiKeyAuthFilter");
    // Reject before any downstream work; correlation-id and MVC handling run after.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
