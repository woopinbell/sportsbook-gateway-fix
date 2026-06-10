package com.sportsbook.gateway.ws;

import com.sportsbook.protocol.event.OddsChanged;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges Kafka {@code odds.changed} to STOMP: decodes the Avro {@link OddsChanged} and broadcasts
 * an {@link OddsUpdate} to {@code /topic/odds/{eventId}}, so clients subscribe per event. Public
 * stream — no per-user routing.
 */
@Component
public class OddsStreamListener {

  private final SimpMessagingTemplate messaging;

  public OddsStreamListener(SimpMessagingTemplate messaging) {
    this.messaging = messaging;
  }

  @KafkaListener(topics = "${gateway.topics.odds-changed}", groupId = "gateway-odds")
  public void onOddsChanged(byte[] payload) {
    OddsChanged event = AvroDecoder.decode(payload, OddsChanged.class);
    messaging.convertAndSend("/topic/odds/" + event.getEventId(), OddsUpdate.from(event));
  }
}
