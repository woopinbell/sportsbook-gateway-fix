package com.sportsbook.gateway.ws;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtException;

class StompAuthChannelInterceptorTest {

  private final StompAuthChannelInterceptor interceptor =
      new StompAuthChannelInterceptor(
          token -> {
            throw new JwtException("not used by these tests");
          });

  @Test
  void rejectsAnonymousAndAuthenticatedClientSendFrames() {
    assertThatThrownBy(
            () -> interceptor.preSend(frame(StompCommand.SEND, "/topic/odds/e-1", null), null))
        .isInstanceOf(MessageDeliveryException.class)
        .hasMessageContaining("SEND");

    assertThatThrownBy(
            () ->
                interceptor.preSend(
                    frame(StompCommand.SEND, "/topic/odds/e-1", () -> "user-1"), null))
        .isInstanceOf(MessageDeliveryException.class)
        .hasMessageContaining("SEND");
  }

  @Test
  void allowsOnlyPublicOddsAndAuthenticatedBetSubscriptions() {
    assertThatCode(
            () ->
                interceptor.preSend(
                    frame(StompCommand.SUBSCRIBE, "/topic/odds/event-1", null), null))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                interceptor.preSend(
                    frame(StompCommand.SUBSCRIBE, "/user/queue/bets", () -> "authenticated-user"),
                    null))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/bets", null), null))
        .isInstanceOf(MessageDeliveryException.class);
    assertThatThrownBy(
            () ->
                interceptor.preSend(
                    frame(StompCommand.SUBSCRIBE, "/queue/internal", () -> "authenticated-user"),
                    null))
        .isInstanceOf(MessageDeliveryException.class);
  }

  private static Message<byte[]> frame(
      StompCommand command, String destination, Principal principal) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setSessionId("test-session");
    accessor.setDestination(destination);
    accessor.setUser(principal);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
