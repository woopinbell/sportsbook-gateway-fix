package com.sportsbook.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Routing over real HTTP (random port + WireMock downstream): public path is rewritten to the
 * internal contract, the verified identity is forwarded (client-spoofed headers ignored), the
 * wallet path embeds the subject, the bets-list query gets the subject, W3C traceparent is
 * propagated, and a downstream RFC 7807 body passes straight back. Rate limiting is disabled here
 * so routing is tested in isolation.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"gateway.ratelimit.enabled=false", "management.tracing.sampling.probability=1.0"})
class GatewayRoutingTest {

  private static final KeyPair ISSUER = rsa();
  private static final String ACTOR_ID = "11111111-1111-4111-8111-111111111111";
  private static final String OTHER_USER_ID = "22222222-2222-4222-8222-222222222222";
  private static final String BET_ID = "33333333-3333-4333-8333-333333333333";
  private static WireMockServer downstream;

  @Autowired private TestRestTemplate rest;

  @TestConfiguration
  static class DecoderConfig {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) ISSUER.getPublic()).build();
    }
  }

  @BeforeAll
  static void startDownstream() {
    // Plain HTTP/1.1 only: the JDK HttpClient the proxy uses negotiates HTTP/2 with WireMock's
    // Jetty otherwise, which resets the stream (RST_STREAM). Real downstreams are HTTP/1.1.
    downstream =
        new WireMockServer(options().dynamicPort().http2PlainDisabled(true).http2TlsDisabled(true));
    downstream.start();
  }

  @AfterAll
  static void stopDownstream() {
    downstream.stop();
  }

  @BeforeEach
  void resetStubs() {
    downstream.resetAll();
  }

  @DynamicPropertySource
  static void downstreamUris(DynamicPropertyRegistry registry) {
    registry.add("gateway.downstream.betting-uri", () -> "http://localhost:" + downstream.port());
    registry.add("gateway.downstream.wallet-uri", () -> "http://localhost:" + downstream.port());
    registry.add("gateway.downstream.odds-feed-uri", () -> "http://localhost:" + downstream.port());
  }

  @Test
  void placeBet_forwardsVerifiedUuidActor_andPreservesBody() {
    downstream.stubFor(
        post(urlEqualTo("/internal/v1/bets")).willReturn(okJson("{}").withStatus(201)));

    String body = "{\n  \"userId\": \"" + OTHER_USER_ID + "\",\n  \"stake\": 1000\n}";
    HttpHeaders headers = bearer(ACTOR_ID);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-User-Id", OTHER_USER_ID);
    headers.set("X-User-Roles", "ADMIN");

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/bets", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    List<LoggedRequest> requests =
        downstream.findAll(postRequestedFor(urlEqualTo("/internal/v1/bets")));
    assertThat(requests).hasSize(1);
    LoggedRequest forwarded = requests.get(0);
    assertThat(forwarded.getHeaders().getHeader("X-User-Id").values()).containsExactly(ACTOR_ID);
    assertThat(forwarded.getHeaders().getHeader("X-User-Roles").values()).containsExactly("USER");
    assertThat(new String(forwarded.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
  }

  @Test
  void listBets_forwardsVerifiedUuidActor_andOverridesSpoofedUserId() {
    downstream.stubFor(
        get(urlPathEqualTo("/internal/v1/bets")).willReturn(okJson("{\"items\":[]}")));

    HttpHeaders headers = bearer(ACTOR_ID);
    headers.set("X-User-Id", OTHER_USER_ID);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/bets?userId=" + OTHER_USER_ID + "&cursor=abc",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    List<LoggedRequest> requests =
        downstream.findAll(getRequestedFor(urlPathEqualTo("/internal/v1/bets")));
    assertThat(requests).hasSize(1);
    LoggedRequest forwarded = requests.get(0);
    assertThat(forwarded.getHeaders().getHeader("X-User-Id").values()).containsExactly(ACTOR_ID);
    assertThat(forwarded.queryParameter("userId").values()).containsExactly(ACTOR_ID);
    assertThat(forwarded.queryParameter("cursor").values()).containsExactly("abc");
  }

  @Test
  void getBet_forwardsVerifiedUuidActor() {
    String downstreamPath = "/internal/v1/bets/" + BET_ID;
    downstream.stubFor(
        get(urlEqualTo(downstreamPath)).willReturn(okJson("{\"id\":\"" + BET_ID + "\"}")));

    HttpHeaders headers = bearer(ACTOR_ID);
    headers.set("X-User-Id", OTHER_USER_ID);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/bets/" + BET_ID, HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    List<LoggedRequest> requests = downstream.findAll(getRequestedFor(urlEqualTo(downstreamPath)));
    assertThat(requests).hasSize(1);
    LoggedRequest forwarded = requests.get(0);
    assertThat(forwarded.getHeaders().getHeader("X-User-Id").values()).containsExactly(ACTOR_ID);
    assertThat(forwarded.getHeaders().getHeader("X-User-Roles").values()).containsExactly("USER");
  }

  @Test
  void walletBalance_embedsSubjectInDownstreamPath() {
    downstream.stubFor(
        get(urlPathMatching("/internal/v1/wallet/accounts/.*/balance"))
            .willReturn(okJson("{\"balance\":0}")));

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/wallet/balance",
            HttpMethod.GET,
            new HttpEntity<>(bearer("user-9")),
            String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    downstream.verify(
        getRequestedFor(urlEqualTo("/internal/v1/wallet/accounts/user-9/balance"))
            .withHeader("X-User-Id", equalTo("user-9")));
  }

  @Test
  void publicOddsFeedRead_isProxiedWithoutAuthentication() {
    downstream.stubFor(get(urlPathEqualTo("/api/v1/events")).willReturn(okJson("{\"items\":[]}")));

    ResponseEntity<String> response = rest.getForEntity("/api/v1/events", String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    downstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/events")));
  }

  @Test
  void downstreamProblemDetail_passesThroughUnchanged() {
    downstream.stubFor(
        post(urlEqualTo("/internal/v1/bets"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"type\":\"https://api.sportsbook.com/problems/insufficient-balance\","
                            + "\"title\":\"Insufficient balance\",\"status\":422,"
                            + "\"errorCode\":\"WALLET_INSUFFICIENT_BALANCE\"}")));

    HttpHeaders headers = bearer("user-1");
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/v1/bets", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("application/problem+json");
    assertThat(response.getBody()).contains("WALLET_INSUFFICIENT_BALANCE");
  }

  @Test
  void traceparent_isPropagatedToDownstream() {
    downstream.stubFor(get(urlPathEqualTo("/api/v1/events")).willReturn(okJson("{}")));
    String traceId = "0af7651916cd43dd8448eb211c80319c";

    HttpHeaders headers = new HttpHeaders();
    headers.set("traceparent", "00-" + traceId + "-b7ad6b7169203331-01");
    rest.exchange("/api/v1/events", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    downstream.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/events"))
            .withHeader("traceparent", containing(traceId)));
  }

  private static HttpHeaders bearer(String subject) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(mint(subject));
    return headers;
  }

  private static String mint(String subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("roles", List.of("USER"))
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(Instant.now().plusSeconds(300)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(ISSUER.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to mint test token", e);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
