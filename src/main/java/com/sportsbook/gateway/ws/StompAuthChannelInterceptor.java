package com.sportsbook.gateway.ws;

import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP sessions on the CONNECT frame (ADR-0011). A {@code Authorization: Bearer
 * <jwt>} header is verified with the same RSA key as the REST side; the user principal is named by
 * the JWT subject so {@code convertAndSendToUser(userId, ...)} reaches exactly that session.
 * CONNECT without a token is allowed but anonymous — it may only use the public odds stream.
 * SUBSCRIBE is allowlisted to the public odds stream and the authenticated user's bet queue. Client
 * SEND frames are always rejected: this gateway is a server-to-client Kafka fan-out and has no
 * client-originated messaging commands.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private final JwtDecoder jwtDecoder;

  public StompAuthChannelInterceptor(JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      authenticate(accessor);
    } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      authorizeSubscription(accessor);
    } else if (StompCommand.SEND.equals(accessor.getCommand())) {
      throw new MessageDeliveryException("Client SEND frames are not supported");
    }
    return message;
  }

  private void authenticate(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ")) {
      return; // anonymous session — only the public odds stream is usable
    }
    try {
      Jwt jwt = jwtDecoder.decode(header.substring("Bearer ".length()));
      accessor.setUser(new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject()));
    } catch (JwtException e) {
      throw new MessageDeliveryException("Invalid or expired token");
    }
  }

  private static void authorizeSubscription(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    if (destination != null
        && destination.startsWith("/topic/odds/")
        && destination.length() > "/topic/odds/".length()) {
      return;
    }
    if ("/user/queue/bets".equals(destination) && accessor.getUser() != null) {
      return;
    }
    throw new MessageDeliveryException("Subscription is not allowed for " + destination);
  }

  private static Collection<GrantedAuthority> authorities(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    if (roles == null) {
      return List.of();
    }
    return roles.stream()
        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
        .toList();
  }
}
