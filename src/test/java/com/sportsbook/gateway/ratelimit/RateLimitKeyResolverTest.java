package com.sportsbook.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.gateway.ratelimit.RateLimitKeyResolver.ResolvedKey;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Bucket selection: per-user when authenticated, per-IP otherwise (X-Forwarded-For aware). */
class RateLimitKeyResolverTest {

  private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();
  private final RateLimitProperties props =
      new RateLimitProperties(
          true,
          new RateLimitProperties.Limit(120, Duration.ofMinutes(1)),
          new RateLimitProperties.Limit(60, Duration.ofMinutes(1)));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedRequest_keysByUserSubjectWithUserLimit() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("user-42")
            .claim("roles", List.of("USER"))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER")));

    ResolvedKey key = resolver.resolve(new MockHttpServletRequest(), props);

    assertThat(key.value()).isEqualTo("user:user-42");
    assertThat(key.limit().capacity()).isEqualTo(120);
  }

  @Test
  void anonymousToken_keysByRemoteAddrWithIpLimit() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "k", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.9");

    ResolvedKey key = resolver.resolve(request, props);

    assertThat(key.value()).isEqualTo("ip:198.51.100.9");
    assertThat(key.limit().capacity()).isEqualTo(60);
  }

  @Test
  void noAuthentication_keysByRemoteAddr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.5");

    assertThat(resolver.resolve(request, props).value()).isEqualTo("ip:203.0.113.5");
  }

  @Test
  void behindProxy_usesForwardedForFirstHop() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

    assertThat(resolver.resolve(request, props).value()).isEqualTo("ip:203.0.113.7");
  }
}
