package com.sportsbook.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end rate limiting against a real Redis (Testcontainers): the per-IP bucket (capacity 3)
 * lets three anonymous requests through (each proxied to a stubbed odds-feed), then returns 429
 * with an RFC 7807 body, a Retry-After header, and a zeroed remaining-tokens header.
 */
@SpringBootTest(
    properties = {"gateway.ratelimit.ip.capacity=3", "gateway.ratelimit.ip.refill-period=1m"})
@AutoConfigureMockMvc
@Testcontainers
class RateLimitTest {

  private static final int IP_CAPACITY = 3;

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  // The public read route now proxies; stub odds-feed so allowed requests get a real 200.
  private static WireMockServer oddsFeed;

  @Autowired private MockMvc mvc;

  @BeforeAll
  static void startOddsFeed() {
    oddsFeed =
        new WireMockServer(options().dynamicPort().http2PlainDisabled(true).http2TlsDisabled(true));
    oddsFeed.start();
    oddsFeed.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/v1/events"))
            .willReturn(okJson("{\"items\":[]}")));
  }

  @AfterAll
  static void stopOddsFeed() {
    oddsFeed.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("gateway.downstream.odds-feed-uri", () -> "http://localhost:" + oddsFeed.port());
  }

  @Test
  void anonymousTraffic_isLimitedPerIp_thenReturns429ProblemJson() throws Exception {
    RequestPostProcessor fromIp =
        request -> {
          request.setRemoteAddr("203.0.113.50");
          return request;
        };

    // Public read API is anonymous, so it is charged to the per-IP bucket; the first `capacity`
    // requests pass the limiter and are proxied (200).
    for (int i = 0; i < IP_CAPACITY; i++) {
      mvc.perform(get("/api/v1/events").with(fromIp)).andExpect(status().is(not(429)));
    }

    mvc.perform(get("/api/v1/events").with(fromIp))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "0"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("GATEWAY_RATE_LIMITED"))
        .andExpect(jsonPath("$.status").value(429));
  }
}
