package com.sportsbook.gateway.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * 실시간 메시지를 전달하는 STOMP WebSocket 설정입니다. {@code /ws/v1/odds}는 공개 배당, {@code /ws/v1/bets}는 인증한 사용자의 베팅
 * 상태를 전달합니다. 메모리 기반 단순 브로커는 배당 방송용 {@code /topic}과 사용자별 {@code /queue}를 처리합니다. CONNECT 프레임 인증은 입력
 * 채널의 {@link StompAuthChannelInterceptor}가 담당합니다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final int MESSAGE_SIZE_LIMIT_BYTES = 64 * 1024;
  private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
  private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

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

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration
        .setMessageSizeLimit(MESSAGE_SIZE_LIMIT_BYTES)
        .setSendBufferSizeLimit(SEND_BUFFER_LIMIT_BYTES)
        .setSendTimeLimit(SEND_TIME_LIMIT_MILLIS);
  }
}
