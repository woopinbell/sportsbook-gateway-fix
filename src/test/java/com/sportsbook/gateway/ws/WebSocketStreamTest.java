package com.sportsbook.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sportsbook.protocol.event.BetSettled;
import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.OddsChanged;
import com.sportsbook.protocol.event.SettlementResultAvro;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * STOMP fan-out end-to-end (random port + embedded Kafka): an {@code odds.changed} event is
 * broadcast to public {@code /topic/odds/{eventId}} subscribers, and a {@code bet.settled} event is
 * pushed only to the owning user's {@code /user/queue/bets} (authenticated CONNECT).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.ratelimit.enabled=false",
      // Deterministic: read from the start so a publish is never missed by group rebalance timing.
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@EmbeddedKafka(
    partitions = 1,
    topics = {"odds.changed", "bet.settled", "bet.voided"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class WebSocketStreamTest {

  private static final KeyPair ISSUER = rsa();
  private static final long RECEIVE_TIMEOUT_SECONDS = 10;

  @LocalServerPort private int port;
  @Autowired private EmbeddedKafkaBroker broker;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;

  @TestConfiguration
  static class DecoderConfig {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) ISSUER.getPublic()).build();
    }
  }

  @BeforeEach
  void awaitConsumers() {
    for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
      ContainerTestUtils.waitForAssignment(container, 1);
    }
  }

  @Test
  void oddsChange_isBroadcastToTopicSubscribers() throws Exception {
    String eventId = UUID.randomUUID().toString();
    BlockingQueue<String> received = new LinkedBlockingQueue<>();

    StompSession session = connect("/ws/v1/odds", null);
    subscribe(session, "/topic/odds/" + eventId, received);

    OddsChanged event =
        OddsChanged.newBuilder()
            .setEventId(eventId)
            .setMarketId(UUID.randomUUID().toString())
            .setSelectionId(UUID.randomUUID().toString())
            .setPreviousOdds("1.8500")
            .setNewOdds("1.9000")
            .setChangedAt(Instant.now())
            .build();
    publish("odds.changed", eventId, event);

    String message = received.poll(RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(message).isNotNull();
    assertThat(message)
        .contains("\"newOdds\":\"1.9000\"")
        .contains("\"eventId\":\"" + eventId + "\"");
  }

  @Test
  void betSettled_isPushedToOwningUserQueue() throws Exception {
    String userId = "user-" + UUID.randomUUID();
    BlockingQueue<String> received = new LinkedBlockingQueue<>();

    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + mint(userId));
    StompSession session = connect("/ws/v1/bets", connectHeaders);
    subscribe(session, "/user/queue/bets", received);

    Money money = Money.newBuilder().setAmount(25000).setCurrency("KRW").build();
    BetSettled event =
        BetSettled.newBuilder()
            .setBetId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setEventId(UUID.randomUUID().toString())
            .setResult(SettlementResultAvro.WON)
            .setStake(money)
            .setPayout(money)
            .setSettledAt(Instant.now())
            .build();
    publish("bet.settled", event.getEventId(), event);

    String message = received.poll(RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(message).isNotNull();
    assertThat(message)
        .contains("\"status\":\"SETTLED\"")
        .contains("\"result\":\"WON\"")
        .contains("\"userId\":\"" + userId + "\"");
  }

  private StompSession connect(String path, StompHeaders connectHeaders) throws Exception {
    // Default SimpleMessageConverter + a byte[] payload type: the broker broadcasts
    // application/json, which a StringMessageConverter would refuse to convert.
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    String url = "ws://localhost:" + port + path;
    StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {};
    if (connectHeaders == null) {
      return client.connectAsync(url, handler).get(5, TimeUnit.SECONDS);
    }
    return client
        .connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, handler)
        .get(5, TimeUnit.SECONDS);
  }

  private static void subscribe(
      StompSession session, String destination, BlockingQueue<String> sink)
      throws InterruptedException {
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            sink.add(new String((byte[]) payload, StandardCharsets.UTF_8));
          }
        });
    // Give the broker a moment to register the subscription before the event is published.
    Thread.sleep(300);
  }

  private void publish(String topic, String key, SpecificRecord record) throws Exception {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(topic, key, encode(record))).get();
    }
  }

  private static <T extends SpecificRecord> byte[] encode(T record) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DatumWriter<T> writer = new SpecificDatumWriter<>(record.getSchema());
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    writer.write(record, encoder);
    encoder.flush();
    return out.toByteArray();
  }

  private static String mint(String subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("roles", List.of("USER"))
              .issueTime(Date.from(Instant.now().minusSeconds(5)))
              .expirationTime(Date.from(Instant.now().plusSeconds(300)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(ISSUER.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to mint test token", e);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
