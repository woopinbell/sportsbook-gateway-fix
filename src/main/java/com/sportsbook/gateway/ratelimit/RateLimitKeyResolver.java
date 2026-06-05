package com.sportsbook.gateway.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Chooses the bucket a request is charged against: authenticated requests are limited per user (JWT
 * subject), anonymous ones per client IP. Behind a load balancer the real client is the first hop
 * of {@code X-Forwarded-For}; otherwise the socket address is used.
 */
@Component
public class RateLimitKeyResolver {

  public ResolvedKey resolve(HttpServletRequest request, RateLimitProperties properties) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getPrincipal() instanceof Jwt jwt
        && StringUtils.hasText(jwt.getSubject())) {
      return new ResolvedKey("user:" + jwt.getSubject(), properties.user());
    }
    return new ResolvedKey("ip:" + clientIp(request), properties.ip());
  }

  static String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(forwardedFor)) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  /** The bucket id a request maps to, plus the limit to apply to it. */
  public record ResolvedKey(String value, RateLimitProperties.Limit limit) {}
}
