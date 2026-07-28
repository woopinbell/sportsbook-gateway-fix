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
 * CONNECT 프레임에서 STOMP 세션을 인증합니다(ADR-0011). {@code Authorization: Bearer <jwt>} 헤더는 REST와 같은 RSA 키로
 * 검증하며 JWT subject를 사용자 식별자로 사용합니다. 토큰 없이 연결한 익명 세션은 공개 배당만 구독할 수 있습니다. SUBSCRIBE는 공개 배당과 인증한 사용자의
 * 베팅 큐만 허용하고, 클라이언트의 SEND 프레임은 모두 거절합니다.
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
      return; // 익명 세션은 공개 배당만 구독할 수 있습니다.
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
