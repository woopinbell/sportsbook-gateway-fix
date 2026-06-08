package com.sportsbook.gateway.routing;

import com.sportsbook.gateway.security.GatewayHeaders;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Adds the trusted identity headers ({@link GatewayHeaders}) to a proxied request from the verified
 * JWT. Inbound copies were already stripped by {@code TrustedHeaderFilter}, so downstream services
 * see only the gateway-asserted values. No-op for anonymous (public) routes.
 */
@Component
public class IdentityForwarding {

  /**
   * Returns the request with X-User-Id / X-User-Roles set from the current JWT (if authenticated).
   */
  public ServerRequest apply(ServerRequest request) {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      return request;
    }
    ServerRequest.Builder builder = ServerRequest.from(request);
    if (StringUtils.hasText(jwt.getSubject())) {
      builder.header(GatewayHeaders.USER_ID, jwt.getSubject());
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    if (roles != null && !roles.isEmpty()) {
      builder.header(GatewayHeaders.USER_ROLES, String.join(",", roles));
    }
    return builder.build();
  }

  /** The authenticated user id (JWT subject), or empty when the request is anonymous. */
  public Optional<String> currentSubject() {
    Jwt jwt = currentJwt();
    return jwt == null ? Optional.empty() : Optional.ofNullable(jwt.getSubject());
  }

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    return null;
  }
}
