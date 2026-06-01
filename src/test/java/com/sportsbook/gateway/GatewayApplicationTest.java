package com.sportsbook.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scaffold smoke test: no Spring context (a full {@code @SpringBootTest} needs Redis + Kafka, which
 * later tasks bring up via Testcontainers). This just proves the test harness compiles and runs.
 */
class GatewayApplicationTest {

  @Test
  void isAnnotatedAsSpringBootApplication() {
    assertThat(GatewayApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
