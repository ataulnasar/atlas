package com.atlas.core.security;

import com.atlas.core.document.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a valid {@code X-API-Key} header on the requests it is registered for (only {@code
 * /api/**} — see {@link ApiKeyAuthConfig}). A plain {@link OncePerRequestFilter} is used rather
 * than Spring Security: the entire requirement is one static header comparison, and pulling in
 * spring-boot-starter-security would add a large dependency whose auto-configuration secures
 * <em>everything</em> by default and then has to be reconfigured — far more machinery than a single
 * header check warrants. This filter is self-contained, dependency-free, and trivial to reason
 * about.
 *
 * <p><b>Keyless-dev mode.</b> When no key is configured (blank {@code ATLAS_API_KEY}) the filter is
 * built {@code enabled = false} and passes every request through. This is a deliberate choice so
 * local dev, CI, and the quickstart run with zero auth friction; the production posture is made
 * explicit by a startup WARN (see {@link ApiKeyAuthConfig}) rather than by a silently-baked default
 * key. Set {@code ATLAS_API_KEY} to require the header.
 *
 * <p>Missing/invalid key → {@code 401} with the standard {@link ApiError} JSON body. The key
 * comparison uses {@link MessageDigest#isEqual} so it does not short-circuit on the first differing
 * byte (constant-time-style), avoiding a timing side channel on the secret.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  public static final String API_KEY_HEADER = "X-API-Key";

  private final boolean enabled;
  private final byte[] expectedKeyBytes;
  private final ObjectMapper objectMapper;

  ApiKeyAuthFilter(String expectedKey, ObjectMapper objectMapper) {
    this.enabled = expectedKey != null && !expectedKey.isBlank();
    this.expectedKeyBytes = enabled ? expectedKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!enabled) {
      chain.doFilter(request, response); // keyless-dev mode: no header required
      return;
    }
    String provided = request.getHeader(API_KEY_HEADER);
    if (provided == null || !constantTimeEquals(provided)) {
      writeUnauthorized(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean constantTimeEquals(String provided) {
    return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKeyBytes);
  }

  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(
        response.getWriter(),
        new ApiError(
            "unauthorized", "Missing or invalid API key. Provide it in the X-API-Key header."));
  }
}
