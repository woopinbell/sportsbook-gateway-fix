# Kafka에서 STOMP로 전달하는 경계

게이트웨이의 WebSocket은 클라이언트 명령을 처리하는 채팅 서버가 아닙니다. Kafka에서
받은 배당과 베팅 상태를 브라우저로 보내는 단방향 통로입니다. HTTP handshake를
열어 두는 것과 STOMP destination을 공개하는 것은 서로 다른 결정입니다.

## handshake와 STOMP 인증

Spring Security는 `/ws/**` handshake를 허용합니다. 공개 배당 사용자는 토큰 없이
연결할 수 있기 때문입니다. `StompAuthChannelInterceptor`가 CONNECT frame의
`Authorization: Bearer ...`를 같은 `JwtDecoder`로 검증하고, subject를 session
principal 이름으로 설정합니다.

토큰이 없는 session은 익명으로 유지합니다. `Bearer` 토큰을 보냈는데 검증이
실패하면 익명으로 낮추지 않고 연결을 거절합니다.

## destination은 허용 목록으로 검사한다

prefix 하나만 막는 방식은 새 broker destination이 생겼을 때 예상하지 못한 구독을
허용하기 쉽습니다. 현재 허용하는 SUBSCRIBE는 두 종류뿐입니다.

- 익명과 인증 사용자: `/topic/odds/{eventId}`
- 인증 사용자: 정확히 `/user/queue/bets`

클라이언트 SEND는 destination과 인증 여부에 관계없이 모두 거절합니다. 단방향
gateway에서 SEND를 열어 두면 클라이언트가 broker topic에 메시지를 주입하거나
application destination을 탐색할 수 있습니다.

`StompAuthChannelInterceptorTest`는 익명·인증 SEND, 익명 사용자 queue, 내부 queue
구독을 모두 실패시키고 두 허용 destination만 통과시킵니다.

## Kafka 이벤트 전달

`OddsStreamListener`는 `odds.changed`를 읽어
`/topic/odds/{eventId}`에 방송합니다. `BetStatusStreamListener`는
`bet.settled.v1`, `bet.voided.v1`을 읽고 event의 user ID를
`convertAndSendToUser()`에 넘깁니다. CONNECT에서 principal 이름을 JWT subject로
설정했기 때문에 해당 사용자 session만 `/user/queue/bets` 메시지를 받습니다.

정산 토픽에는 `.v1`이 붙습니다. producer와 consumer 중 한쪽만 이름을 바꾸면
listener는 정상이어도 메시지를 하나도 받지 못합니다. 토픽 이름은
`gateway.topics.*` 설정과 producer 설정을 함께 확인해야 합니다.

`WebSocketStreamTest`는 embedded Kafka와 실제 STOMP client를 사용합니다.

- 익명 odds session이 배당 변경을 받음
- bearer token으로 연결한 사용자가 자신의 정산 결과를 받음
- Kafka listener가 partition을 할당받은 뒤 발행

구독 직후 바로 발행하면 simple broker가 SUBSCRIBE를 처리하기 전에 event가 지나갈
수 있습니다. 테스트가 listener assignment와 subscription 등록을 기다리는 이유입니다.

## 전송 자원 제한

`WebSocketConfig`는 메시지 64KiB, session send buffer 512KiB, 전송시간 10초를
설정합니다. 느린 client가 무한히 buffer를 쌓지 않게 하는 안전장치입니다. 이 값은
Kafka payload 최대 크기와 별개이므로 producer가 더 큰 event를 만들지 않도록 schema와
운영 정책도 맞춰야 합니다.

## 현재 실패 경계

`AvroDecoder`는 Schema Registry 없이 현재 generated class의 schema로 binary payload를
읽습니다. 호환되지 않거나 손상된 payload는 `IllegalStateException`이 되며 별도의
dead-letter topic 설정은 없습니다. 같은 record가 반복 실패하지 않도록 운영에서는
listener 오류율과 consumer lag를 함께 감시해야 합니다.

내장 simple broker는 한 JVM 안에서만 session을 압니다. gateway를 여러 대로 늘리면
Kafka consumer group이 event를 한 인스턴스에만 전달하는 반면 사용자의 session은
다른 인스턴스에 있을 수 있습니다. 다중 인스턴스 전에는 broker relay나 별도의
분산 fan-out 구조가 필요합니다.

```sh
./mvnw -Dtest=StompAuthChannelInterceptorTest,WebSocketStreamTest test
```
