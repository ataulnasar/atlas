package com.atlas.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter's decision logic, exercised directly (no container). Constant-time comparison ({@link
 * java.security.MessageDigest#isEqual}) is the mechanism; timing itself isn't unit-testable, so
 * these tests pin correctness — including a wrong key of the SAME length, which a naive length-only
 * or short-circuit check could mishandle.
 */
class ApiKeyAuthFilterTest {

  private static final String KEY = "s3cret-key-value";
  private final ObjectMapper objectMapper = new ObjectMapper();

  private MockFilterChain run(String configuredKey, String providedHeader) throws Exception {
    ApiKeyAuthFilter filter = new ApiKeyAuthFilter(configuredKey, objectMapper);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations/x");
    if (providedHeader != null) {
      request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, providedHeader);
    }
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  private MockHttpServletResponse responseOf(String configuredKey, String providedHeader)
      throws Exception {
    ApiKeyAuthFilter filter = new ApiKeyAuthFilter(configuredKey, objectMapper);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations/x");
    if (providedHeader != null) {
      request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, providedHeader);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void missingHeaderIsRejectedWith401AndCleanBody() throws Exception {
    MockHttpServletResponse response = responseOf(KEY, null);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).contains("application/json");
    assertThat(response.getContentAsString()).contains("\"error\":\"unauthorized\"");
    // Chain did not proceed.
    assertThat(run(KEY, null).getRequest()).isNull();
  }

  @Test
  void wrongKeyIsRejected() throws Exception {
    assertThat(responseOf(KEY, "not-the-key").getStatus()).isEqualTo(401);
  }

  @Test
  void wrongKeyOfTheSameLengthIsRejected() throws Exception {
    String sameLengthWrong = "S3CRET-KEY-VALUE"; // same length as KEY, different bytes
    assertThat(sameLengthWrong).hasSameSizeAs(KEY);
    assertThat(responseOf(KEY, sameLengthWrong).getStatus()).isEqualTo(401);
  }

  @Test
  void correctKeyPassesThrough() throws Exception {
    MockFilterChain chain = run(KEY, KEY);
    assertThat(chain.getRequest()).isNotNull(); // chain proceeded
  }

  @Test
  void disabledModePassesThroughWithoutAnyHeader() throws Exception {
    assertThat(run("", null).getRequest()).isNotNull();
    assertThat(run("   ", null).getRequest()).isNotNull();
    assertThat(run(null, null).getRequest()).isNotNull();
  }
}
