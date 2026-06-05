package com.sportsbook.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Resilience: a Redis outage must not take the gateway down. With Redis unreachable the limiter
 * fails open (allows the request) rather than throwing.
 */
class RateLimiterServiceTest {

  @Test
  void failsOpenWhenRedisIsUnreachable() throws IOException {
    RedisClient client =
        RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(closedPort()).build());
    client.setOptions(
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
            .build());
    RateLimiterService service = new RateLimiterService(client);
    try {
      RateLimitResult result =
          service.tryConsume("ip:1.2.3.4", new RateLimitProperties.Limit(5, Duration.ofMinutes(1)));

      assertThat(result.allowed()).isTrue();
      assertThat(result.failOpen()).isTrue();
    } finally {
      service.shutdown();
    }
  }

  /** A port that was just released, so a connection attempt is refused immediately. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
