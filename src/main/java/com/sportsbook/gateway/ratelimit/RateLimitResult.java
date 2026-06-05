package com.sportsbook.gateway.ratelimit;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param remainingTokens tokens left in the bucket (-1 when unknown, e.g. fail-open)
 * @param retryAfterSeconds when denied, seconds until the bucket refills enough for one request
 * @param failOpen true when Redis was unreachable and the request was allowed defensively
 */
public record RateLimitResult(
    boolean allowed, long remainingTokens, long retryAfterSeconds, boolean failOpen) {

  static RateLimitResult allow(long remainingTokens) {
    return new RateLimitResult(true, remainingTokens, 0, false);
  }

  static RateLimitResult deny(long retryAfterSeconds) {
    return new RateLimitResult(false, 0, retryAfterSeconds, false);
  }

  static RateLimitResult failedOpen() {
    return new RateLimitResult(true, -1, 0, true);
  }
}
