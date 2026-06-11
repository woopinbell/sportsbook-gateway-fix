package com.sportsbook.gateway.ws;

import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.BetVoided;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges Kafka {@code bet.settled.v1} / {@code bet.voided.v1} to STOMP: decodes the Avro event and
 * pushes a {@link BetStatusUpdate} to the owning user's {@code /user/queue/bets}. Spring resolves
 * the destination to the session(s) whose principal name equals the event's {@code userId}, so a
 * user only ever receives their own bet updates (the principal is named by the JWT subject in
 * {@link StompAuthChannelInterceptor}).
 */
@Component
public class BetStatusStreamListener {

  private static final String USER_DESTINATION = "/queue/bets";

  private final SimpMessagingTemplate messaging;

  public BetStatusStreamListener(SimpMessagingTemplate messaging) {
    this.messaging = messaging;
  }

  @KafkaListener(topics = "${gateway.topics.bet-settled}", groupId = "gateway-bets")
  public void onBetSettled(byte[] payload) {
    BetSettled event = AvroDecoder.decode(payload, BetSettled.class);
    messaging.convertAndSendToUser(
        event.getUserId(), USER_DESTINATION, BetStatusUpdate.settled(event));
  }

  @KafkaListener(topics = "${gateway.topics.bet-voided}", groupId = "gateway-bets")
  public void onBetVoided(byte[] payload) {
    BetVoided event = AvroDecoder.decode(payload, BetVoided.class);
    messaging.convertAndSendToUser(
        event.getUserId(), USER_DESTINATION, BetStatusUpdate.voided(event));
  }
}
