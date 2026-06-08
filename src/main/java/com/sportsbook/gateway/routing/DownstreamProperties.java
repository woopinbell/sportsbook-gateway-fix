package com.sportsbook.gateway.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Base URIs of the internal services the gateway proxies to. Defaults match the local ports in each
 * service's {@code application.yml} (wallet 8081, betting 8082, odds-feed 8085); override per
 * environment via {@code gateway.downstream.*} or the {@code GATEWAY_*_URI} env vars.
 */
@ConfigurationProperties(prefix = "gateway.downstream")
public record DownstreamProperties(
    @DefaultValue("http://localhost:8082") String bettingUri,
    @DefaultValue("http://localhost:8081") String walletUri,
    @DefaultValue("http://localhost:8085") String oddsFeedUri) {}
