package com.sportsbook.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bucket4j and Redis rate-limit policy. Authenticated traffic is limited per user (JWT subject);
 * anonymous traffic per client IP. Defaults live here and in {@code application.yml}; both are
 * token-bucket limits (capacity + greedy refill).
 */
@ConfigurationProperties(prefix = "gateway.ratelimit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled, @DefaultValue Limit user, @DefaultValue Limit ip) {

  /** A token bucket: {@code capacity} tokens that refill continuously over {@code refillPeriod}. */
  public record Limit(
      @DefaultValue("100") long capacity, @DefaultValue("1m") Duration refillPeriod) {}
}
