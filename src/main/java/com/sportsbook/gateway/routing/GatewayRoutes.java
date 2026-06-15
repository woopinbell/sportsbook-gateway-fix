package com.sportsbook.gateway.routing;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST routing (Spring Cloud Gateway Server MVC). Maps the public {@code /api/v1/*} surface onto
 * the internal services and rewrites it to their {@code /internal/v1/*} contract (ADR-0004). Every
 * authenticated route forwards the verified identity ({@link IdentityForwarding}) and a W3C
 * traceparent ({@link TraceForwarding}); the downstream's RFC 7807 error bodies pass straight back
 * to the client (the proxy relays the response unchanged).
 *
 * <p>How the proxy composes the target URL: {@code http(baseUri)} supplies scheme + host + port,
 * while each before-filter rewrites the {@code ServerRequest} URI (path + query). The two are
 * combined for the outbound call — the same mechanism the framework's own {@code rewritePath} uses.
 */
@Configuration
@EnableConfigurationProperties(DownstreamProperties.class)
public class GatewayRoutes {

  private final DownstreamProperties downstream;
  private final IdentityForwarding identity;
  private final TraceForwarding trace;

  public GatewayRoutes(
      DownstreamProperties downstream, IdentityForwarding identity, TraceForwarding trace) {
    this.downstream = downstream;
    this.identity = identity;
    this.trace = trace;
  }

  /** POST/GET bets → betting-service. Identity is forwarded; see {@link #forwardBets}. */
  @Bean
  RouterFunction<ServerResponse> bettingRoute() {
    return route("betting")
        .route(path("/api/v1/bets").or(path("/api/v1/bets/**")), http(downstream.bettingUri()))
        .before(identity::apply)
        .before(trace::apply)
        .before(this::forwardBets)
        .build();
  }

  /** GET wallet balance → wallet-service, with the subject embedded in the path. */
  @Bean
  RouterFunction<ServerResponse> walletBalanceRoute() {
    return route("wallet-balance")
        .route(GET("/api/v1/wallet/balance"), http(downstream.walletUri()))
        .before(identity::apply)
        .before(trace::apply)
        .before(this::forwardWalletBalance)
        .build();
  }

  /**
   * Public read API (events + odds) → odds-feed-service, unchanged path (already {@code /api/v1}).
   */
  @Bean
  RouterFunction<ServerResponse> oddsFeedReadRoute() {
    return route("odds-feed-read")
        .route(
            path("/api/v1/events").or(path("/api/v1/events/**")).or(path("/api/v1/odds/**")),
            http(downstream.oddsFeedUri()))
        .before(trace::apply)
        .build();
  }

  /**
   * Rewrites {@code /api/v1/bets...} to {@code /internal/v1/bets...}. For the collection GET it
   * also injects {@code userId=<subject>} (overriding any client value) so a user can only list
   * their own bets. The gateway preserves the POST body byte-for-byte; betting requires the
   * forwarded X-User-Id and rejects a body actor mismatch.
   */
  private ServerRequest forwardBets(ServerRequest request) {
    String rewrittenPath =
        request.uri().getRawPath().replaceFirst("^/api/v1/bets", "/internal/v1/bets");
    URI rewrittenUri =
        UriComponentsBuilder.fromUri(request.uri()).replacePath(rewrittenPath).build(true).toUri();
    ServerRequest.Builder builder = ServerRequest.from(request).uri(rewrittenUri);
    // The proxy reads the downstream query from ServerRequest.params(), not the URI; inject userId
    // there. set() overrides any client-supplied value so a user can only list their own bets.
    if (HttpMethod.GET.equals(request.method()) && "/internal/v1/bets".equals(rewrittenPath)) {
      identity
          .currentSubject()
          .ifPresent(subject -> builder.params(params -> params.set("userId", subject)));
    }
    return builder.build();
  }

  /**
   * Rewrites {@code /api/v1/wallet/balance} to wallet's {@code
   * /internal/v1/wallet/accounts/{sub}/balance}.
   */
  private ServerRequest forwardWalletBalance(ServerRequest request) {
    String subject = identity.currentSubject().orElse(null);
    if (subject == null) {
      return request; // unreachable: route is authenticated, but stay defensive
    }
    URI rewritten =
        UriComponentsBuilder.fromUri(request.uri())
            .replacePath("/internal/v1/wallet/accounts/" + subject + "/balance")
            .build(true)
            .toUri();
    return withUri(request, rewritten);
  }

  private static ServerRequest withUri(ServerRequest request, URI uri) {
    return ServerRequest.from(request).uri(uri).build();
  }
}
