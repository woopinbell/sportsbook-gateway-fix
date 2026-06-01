package com.sportsbook.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Public API gateway entry point (ADR-0004 / ADR-0011): the single front door for external clients.
 * Verifies RS256 JWTs, enforces per-user / per-IP rate limits, routes REST traffic to the internal
 * services, and fans out live odds + bet-status over STOMP WebSocket. Runs on the servlet stack
 * (Spring Cloud Gateway Server MVC) so the STOMP broker can coexist — see {@code pom.xml} for the
 * WebFlux-vs-STOMP reconciliation note.
 */
// @SpringBootApplication is meta-annotated with @Configuration, so Spring instantiates this class
// as a bean; a private constructor would break that. Suppress the utility-class rule explicitly.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
