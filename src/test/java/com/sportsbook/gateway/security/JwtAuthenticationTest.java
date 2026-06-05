package com.sportsbook.gateway.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge JWT verification (ADR-0011): the gateway accepts only tokens signed by the issuer's key,
 * rejects expired / forged ones with 401, and leaves the public read API open. Keys are generated
 * per run (no committed private key); the decoder is pinned to the matching public key so signing
 * here mirrors a real issuer.
 */
// Rate limiting is exercised in its own test; disable it here so these auth assertions don't
// depend on Redis (the limiter would otherwise fail open after a connect timeout).
@SpringBootTest(properties = "gateway.ratelimit.enabled=false")
@AutoConfigureMockMvc
class JwtAuthenticationTest {

  // Issuer keys the decoder trusts, plus an unrelated pair to forge with.
  private static final KeyPair ISSUER = rsa();
  private static final KeyPair ATTACKER = rsa();

  private static final String SECURED_PATH = "/api/v1/_probe/secured";

  @Autowired private MockMvc mvc;

  @TestConfiguration
  static class DecoderConfig {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) ISSUER.getPublic()).build();
    }
  }

  @Test
  void securedPath_withoutToken_isUnauthorized() throws Exception {
    mvc.perform(get(SECURED_PATH)).andExpect(status().isUnauthorized());
  }

  @Test
  void securedPath_withValidToken_passesSecurity() throws Exception {
    String token = mint(ISSUER, "user-1", List.of("USER"), Instant.now().plusSeconds(300));
    // Passes auth; there is no handler for the probe path yet, so the outcome is simply "not 401".
    mvc.perform(get(SECURED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().is(not(401)));
  }

  @Test
  void expiredToken_isUnauthorized() throws Exception {
    String token = mint(ISSUER, "user-1", List.of("USER"), Instant.now().minusSeconds(60));
    mvc.perform(get(SECURED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void forgedToken_signedWithWrongKey_isUnauthorized() throws Exception {
    String token = mint(ATTACKER, "user-1", List.of("USER"), Instant.now().plusSeconds(300));
    mvc.perform(get(SECURED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void publicReadApi_eventsWithoutToken_isNotRejected() throws Exception {
    mvc.perform(get("/api/v1/events")).andExpect(status().is(not(401)));
  }

  @Test
  void actuatorLivenessProbe_isPublic() throws Exception {
    // Liveness reflects only app state (no Redis/Kafka), so it is a stable 200 and proves the
    // probe path is unauthenticated. The aggregate /actuator/health would be 503 here because
    // Redis is down in this slice — that is correct readiness behaviour, not an auth failure.
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  private static String mint(KeyPair signer, String subject, List<String> roles, Instant expiry) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("roles", roles)
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(expiry))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(signer.getPrivate()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to mint test token", e);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
