package com.sportsbook.gateway.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Distributed token-bucket rate limiter (Bucket4j over Redis/Lettuce). Buckets live in Redis so the
 * limit holds across gateway instances. The Redis connection and proxy manager are created lazily
 * on first use, and every Redis interaction is wrapped so that a Redis outage <b>fails open</b>
 * (the request is allowed) rather than taking the gateway down — availability of the front door
 * matters more than perfect limiting during a cache blip.
 */
@Service
public class RateLimiterService {

  private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

  private final RedisClient redisClient;

  private volatile StatefulRedisConnection<byte[], byte[]> connection;
  private volatile ProxyManager<byte[]> proxyManager;

  public RateLimiterService(RedisClient redisClient) {
    this.redisClient = redisClient;
  }

  /**
   * Tries to consume one token for {@code key}; allows the request (fail-open) if Redis is down.
   */
  public RateLimitResult tryConsume(String key, RateLimitProperties.Limit limit) {
    try {
      BucketConfiguration configuration =
          BucketConfiguration.builder()
              .addLimit(
                  bandwidth ->
                      bandwidth
                          .capacity(limit.capacity())
                          .refillGreedy(limit.capacity(), limit.refillPeriod()))
              .build();
      BucketProxy bucket =
          proxyManager(limit.refillPeriod())
              .builder()
              .build(key.getBytes(StandardCharsets.UTF_8), () -> configuration);

      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      if (probe.isConsumed()) {
        return RateLimitResult.allow(probe.getRemainingTokens());
      }
      return RateLimitResult.deny(secondsToWait(probe.getNanosToWaitForRefill()));
    } catch (RuntimeException e) {
      log.warn(
          "Rate limiter degraded, failing open for key prefix '{}': {}",
          keyPrefix(key),
          e.toString());
      return RateLimitResult.failedOpen();
    }
  }

  private static long secondsToWait(long nanos) {
    Duration wait = Duration.ofNanos(nanos);
    long seconds = wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0); // ceil to whole seconds
    return Math.max(1L, seconds);
  }

  private static String keyPrefix(String key) {
    int colon = key.indexOf(':');
    return colon > 0 ? key.substring(0, colon) : key;
  }

  private ProxyManager<byte[]> proxyManager(Duration refillPeriod) {
    ProxyManager<byte[]> local = proxyManager;
    if (local == null) {
      synchronized (this) {
        if (proxyManager == null) {
          connection = redisClient.connect(ByteArrayCodec.INSTANCE);
          proxyManager =
              LettuceBasedProxyManager.builderFor(connection)
                  .withExpirationStrategy(
                      ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                          refillPeriod.multipliedBy(2)))
                  .build();
        }
        local = proxyManager;
      }
    }
    return local;
  }

  @PreDestroy
  void shutdown() {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (RuntimeException e) {
      log.debug("Error closing rate-limit Redis connection: {}", e.toString());
    }
    try {
      redisClient.shutdown();
    } catch (RuntimeException e) {
      log.debug("Error shutting down rate-limit Redis client: {}", e.toString());
    }
  }
}
