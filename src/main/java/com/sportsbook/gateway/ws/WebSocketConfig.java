package com.sportsbook.gateway.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket broker for live fan-out. Two handshake endpoints: {@code /ws/v1/odds} (public
 * odds stream) and {@code /ws/v1/bets} (authenticated bet-status). An in-memory simple broker
 * serves {@code /topic} (odds broadcast) and {@code /queue} (per-user, via the {@code /user}
 * destination prefix). CONNECT-frame authentication is enforced by {@link
 * StompAuthChannelInterceptor} on the inbound channel.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthChannelInterceptor authInterceptor;
  private final String[] allowedOrigins;

  public WebSocketConfig(
      StompAuthChannelInterceptor authInterceptor,
      @Value("${gateway.ws.allowed-origins}") String[] allowedOrigins) {
    this.authInterceptor = authInterceptor;
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws/v1/odds", "/ws/v1/bets").setAllowedOriginPatterns(allowedOrigins);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }
}
