package com.sportsbook.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.gateway.ratelimit.RateLimitKeyResolver.ResolvedKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the per-user / per-IP rate limit (ADR-0004 RFC 7807 on rejection). Runs just after the
 * Spring Security chain so the authenticated principal is available for per-user keying; requests
 * security already rejected (401) never reach it. Health probes and the error dispatch are exempt;
 * STOMP handshakes consume the anonymous per-IP bucket. On rejection it returns 429 with an {@code
 * application/problem+json} body and a {@code Retry-After} header.
 */
@Component
@Order(RateLimitFilter.ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

  static final int ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 10;
  static final String REMAINING_HEADER = "X-RateLimit-Remaining";

  private static final String PROBLEM_TYPE = "https://api.sportsbook.com/problems/rate-limited";
  private static final String ERROR_CODE = "GATEWAY_RATE_LIMITED";

  private final RateLimitProperties properties;
  private final RateLimiterService limiter;
  private final RateLimitKeyResolver keyResolver;
  private final ObjectMapper objectMapper;

  public RateLimitFilter(
      RateLimitProperties properties,
      RateLimiterService limiter,
      RateLimitKeyResolver keyResolver,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.limiter = limiter;
    this.keyResolver = keyResolver;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!properties.enabled()) {
      return true;
    }
    String path = request.getRequestURI();
    return path.startsWith("/actuator") || path.startsWith("/error");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    ResolvedKey key = keyResolver.resolve(request, properties);
    RateLimitResult result = limiter.tryConsume(key.value(), key.limit());
    if (result.allowed()) {
      if (!result.failOpen()) {
        response.setHeader(REMAINING_HEADER, Long.toString(result.remainingTokens()));
      }
      chain.doFilter(request, response);
      return;
    }
    writeTooManyRequests(request, response, result);
  }

  private void writeTooManyRequests(
      HttpServletRequest request, HttpServletResponse response, RateLimitResult result)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
    problem.setType(URI.create(PROBLEM_TYPE));
    problem.setTitle("Too Many Requests");
    problem.setDetail("Rate limit exceeded. Retry after " + result.retryAfterSeconds() + "s.");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("errorCode", ERROR_CODE);
    problem.setProperty("retryAfterSeconds", result.retryAfterSeconds());
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(result.retryAfterSeconds()));
    response.setHeader(REMAINING_HEADER, "0");
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
