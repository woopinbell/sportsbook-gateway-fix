package com.sportsbook.gateway.security;

/**
 * Trusted identity headers the gateway injects on the proxied request after verifying the JWT.
 * Downstream services read these instead of re-verifying the token (they trust the gateway on the
 * internal network, ADR-0011). Any inbound copy of these headers is stripped first (see {@link
 * TrustedHeaderFilter}) so a client cannot forge an identity.
 */
public final class GatewayHeaders {

  /** Authenticated user id — the JWT {@code sub} claim. */
  public static final String USER_ID = "X-User-Id";

  /** Comma-separated roles — the JWT {@code roles} claim. */
  public static final String USER_ROLES = "X-User-Roles";

  private GatewayHeaders() {
    // Constants holder.
  }
}
