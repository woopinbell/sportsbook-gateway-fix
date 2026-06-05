package com.sportsbook.gateway.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires the Lettuce {@link RedisClient} that backs the distributed rate-limit buckets, built from
 * the same {@code spring.data.redis.*} properties the rest of the app uses. Creating the client
 * opens no connection (Lettuce connects lazily on first use), so the context still starts when
 * Redis is down — the limiter then fails open (see {@link RateLimiterService}). A short socket
 * connect timeout keeps that degraded path fast.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);

  @Bean
  RedisClient rateLimitRedisClient(RedisProperties redis) {
    String host = StringUtils.hasText(redis.getHost()) ? redis.getHost() : "localhost";
    RedisURI.Builder uri = RedisURI.builder().withHost(host).withPort(redis.getPort());
    if (StringUtils.hasText(redis.getPassword())) {
      if (StringUtils.hasText(redis.getUsername())) {
        uri.withAuthentication(redis.getUsername(), redis.getPassword().toCharArray());
      } else {
        uri.withPassword(redis.getPassword().toCharArray());
      }
    }
    if (redis.getDatabase() != 0) {
      uri.withDatabase(redis.getDatabase());
    }
    RedisClient client = RedisClient.create(uri.build());
    client.setOptions(
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
            .build());
    return client;
  }
}
