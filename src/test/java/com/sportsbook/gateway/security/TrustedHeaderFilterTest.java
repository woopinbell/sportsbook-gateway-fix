package com.sportsbook.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Anti-spoofing: client-supplied {@code X-User-*} headers must never reach downstream code. */
class TrustedHeaderFilterTest {

  @Test
  void stripsClientSuppliedIdentityHeadersButKeepsOthers() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(GatewayHeaders.USER_ID, "attacker");
    request.addHeader(GatewayHeaders.USER_ROLES, "ADMIN");
    request.addHeader("Authorization", "Bearer real-token");

    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set((HttpServletRequest) req);

    new TrustedHeaderFilter().doFilter(request, new MockHttpServletResponse(), chain);

    HttpServletRequest wrapped = seen.get();
    assertThat(wrapped.getHeader(GatewayHeaders.USER_ID)).isNull();
    // Case-insensitive: HTTP header names are.
    assertThat(wrapped.getHeader("x-user-roles")).isNull();
    assertThat(wrapped.getHeaders(GatewayHeaders.USER_ID).hasMoreElements()).isFalse();
    assertThat(wrapped.getHeader("Authorization")).isEqualTo("Bearer real-token");
    assertThat(Collections.list(wrapped.getHeaderNames()))
        .doesNotContain(GatewayHeaders.USER_ID, GatewayHeaders.USER_ROLES)
        .contains("Authorization");
  }
}
