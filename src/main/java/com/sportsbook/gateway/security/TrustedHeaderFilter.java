package com.sportsbook.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Removes client-supplied identity headers ({@code X-User-*}) on the way in, before anything reads
 * them, so a caller cannot impersonate a user simply by setting them. The gateway re-injects
 * trusted values derived from the verified JWT onto the proxied request (see the routing filter).
 *
 * <p>Runs at highest precedence so the wrapped request — with these headers hidden — is what both
 * the security chain and the downstream proxy observe. Header matching is case-insensitive because
 * HTTP header names are.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedHeaderFilter extends OncePerRequestFilter {

  private static final Set<String> STRIPPED =
      Set.of(
          GatewayHeaders.USER_ID.toLowerCase(Locale.ROOT),
          GatewayHeaders.USER_ROLES.toLowerCase(Locale.ROOT));

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(new StrippedRequest(request), response);
  }

  private static boolean isStripped(String name) {
    return name != null && STRIPPED.contains(name.toLowerCase(Locale.ROOT));
  }

  /** Hides the stripped headers from every read path of the wrapped request. */
  private static final class StrippedRequest extends HttpServletRequestWrapper {

    StrippedRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      return isStripped(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Set<String> names = new LinkedHashSet<>();
      Enumeration<String> original = super.getHeaderNames();
      while (original.hasMoreElements()) {
        String name = original.nextElement();
        if (!isStripped(name)) {
          names.add(name);
        }
      }
      return Collections.enumeration(names);
    }
  }
}
