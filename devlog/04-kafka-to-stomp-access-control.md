# Kafka 이벤트를 STOMP 구독자에게 나눠 보내기

게이트웨이의 WebSocket은 클라이언트 명령을 처리하는 채팅 서버가 아니다. Kafka에서
받은 배당과 베팅 상태를 브라우저로 보내는 단방향 통로다. HTTP 핸드셰이크를
열어 두는 것과 STOMP 구독 주소를 공개하는 것은 서로 다른 결정이다.

## 핸드셰이크에서 받은 사용자와 CONNECT 토큰

Spring Security는 `/ws/**` 핸드셰이크를 허용한다. 공개 배당 사용자는 토큰 없이
연결할 수 있기 때문이다. 다만 `permitAll` 경로라도 HTTP `Authorization` 헤더에
유효한 토큰이 들어오면 리소스 서버 필터가 인증하고, 그 principal은 WebSocket 세션으로
전달된다.
[`StompAuthChannelInterceptor`](../src/main/java/com/sportsbook/gateway/ws/StompAuthChannelInterceptor.java)가
CONNECT 프레임의 `Authorization: Bearer ...` 헤더를 찾으면 같은 `JwtDecoder`로
다시 검증하고 subject를 세션 principal 이름으로 설정한다. 핸드셰이크에서 이미
만든 principal이 있어도 CONNECT 토큰의 subject로 덮어쓴다. CONNECT 헤더가 없을
때만 핸드셰이크 principal을 그대로 둔다.

세션에 사용자를 연결하는 경로는 HTTP 핸드셰이크 토큰과 STOMP CONNECT 토큰
두 가지다. 둘 다 없으면 익명으로 유지한다. 어느 쪽이든 토큰을 보냈는데 검증이
실패하면 익명으로 낮추지 않고 연결을 거절한다. 두 위치에 서로
다른 유효한 토큰을 보내면 CONNECT 토큰의 사용자가 최종 사용자가 된다.

## 구독 주소는 허용 목록으로 검사한다

브로커에 새 주소가 추가돼도 저절로 공개되지 않도록 SUBSCRIBE를 두 종류로 제한했다.

- 익명과 인증 사용자: `/topic/odds/` 뒤에 한 글자 이상 있는 주소
- 인증 사용자: 정확히 `/user/queue/bets`

배당 리스너가 보내는 주소는 `/topic/odds/{eventId}`지만 인터셉터는 뒷부분이
UUID인지, 경로 조각이 하나뿐인지 검사하지 않는다. `/topic/odds/anything/more`도
통과한다. 이벤트 ID를 엄격히 제한하려면 접두사 검사에 형식 검증을 더해야 한다.

클라이언트 SEND는 주소와 인증 여부에 관계없이 모두 거절한다. 단방향 게이트웨이에서
SEND를 열어 두면 클라이언트가 브로커 토픽에 메시지를 넣거나 애플리케이션 주소를
탐색할 수 있다.

```java
if (StompCommand.CONNECT.equals(accessor.getCommand())) {
  authenticate(accessor);
} else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
  authorizeSubscription(accessor);
} else if (StompCommand.SEND.equals(accessor.getCommand())) {
  throw new MessageDeliveryException("Client SEND frames are not supported");
}
```

[`StompAuthChannelInterceptorTest`](../src/test/java/com/sportsbook/gateway/ws/StompAuthChannelInterceptorTest.java)는
익명·인증 SEND, 익명 사용자의 개인 큐, 내부 큐 구독을 실패시키고 공개 배당 주소와
인증 사용자의 개인 큐가 통과하는지 확인한다.

## Kafka 이벤트 전달

[`OddsStreamListener`](../src/main/java/com/sportsbook/gateway/ws/OddsStreamListener.java)는
`odds.changed`를 읽어
`/topic/odds/{eventId}`에 방송한다.
[`BetStatusStreamListener`](../src/main/java/com/sportsbook/gateway/ws/BetStatusStreamListener.java)는
`bet.settled.v1`, `bet.voided.v1`을 읽고 이벤트의 사용자 ID를
`convertAndSendToUser()`에 넘긴다. HTTP 핸드셰이크나 CONNECT에서 principal 이름이
JWT subject로 정해졌기 때문에 해당 사용자 세션만 `/user/queue/bets` 메시지를
받는다.

정산 토픽에는 `.v1`이 붙는다. 발행자와 소비자 중 한쪽만 이름을 바꾸면 리스너는
정상이어도 메시지를 하나도 받지 못한다. 토픽 이름은 `gateway.topics.*` 설정과
발행 서비스 설정을 함께 확인해야 한다.

[`WebSocketStreamTest`](../src/test/java/com/sportsbook/gateway/ws/WebSocketStreamTest.java)는
내장 Kafka와 실제 STOMP 클라이언트를 사용한다.

- 익명 배당 세션이 배당 변경을 받음
- 토큰으로 연결한 사용자가 자신의 정산 결과를 받음
- Kafka 리스너가 파티션을 할당받은 뒤 발행

구독 직후 바로 발행하면 단순 브로커가 SUBSCRIBE를 처리하기 전에 이벤트가
지나갈 수 있다. 테스트가 리스너의 파티션 할당과 구독 등록을 기다리는
이유다.

## 전송 자원 제한

[`WebSocketConfig`](../src/main/java/com/sportsbook/gateway/ws/WebSocketConfig.java)는
메시지 64KiB, 세션 전송 버퍼 512KiB, 전송 제한 시간 10초를 설정한다. 느린
클라이언트가 버퍼를 끝없이 쌓지 않게 하는 안전장치다. 이 값은 Kafka 레코드
최대 크기와 별개이므로 발행자가 더 큰 이벤트를 만들지 않도록 스키마와 운영
정책도 맞춰야 한다.

## 현재 실패 경계

[`AvroDecoder`](../src/main/java/com/sportsbook/gateway/ws/AvroDecoder.java)는
Schema Registry 없이 생성 클래스의 스키마로 이진 레코드를 읽는다. 호환되지
않거나 손상된 레코드는 `IllegalStateException`이 된다.

[`pom.xml`](../pom.xml)의 Spring Boot 3.2.11은 Spring Kafka 3.1.9를 사용한다. 이 저장소에는
`CommonErrorHandler`, 재시도 정책, 실패 레코드 보관 토픽(DLT)을 따로 구성한 코드가
없다.
따라서 기본 `DefaultErrorHandler`가 최초 처리 뒤 대기 없이 9회 더 시도한다.
열 번째 처리도 실패하면 기본 복구기가 레코드와 예외를 로그로 남기고, 컨테이너는
해당 레코드를 처리한 것으로 보고 다음 레코드로 진행한다. 기본 `BATCH` 커밋
방식에서는 이 오프셋도 이후 커밋에 포함된다.

그 사이 메시지는 STOMP로 전달되지 않으며 별도로 보관되는 토픽도 없다. Kafka에
원본 보존 기간이 남아 있더라도 이 소비자 그룹의 실시간 갱신에서는 유실된 셈이다.
복구가 필요하다면 DLT와 재처리 절차를 함께 두어야 한다.

내장 단순 브로커는 한 JVM 안에서만 세션을 안다. 게이트웨이를 여러 대로 늘리면
Kafka 소비자 그룹이 이벤트를 한 인스턴스에만 전달하는 반면 사용자 세션은 다른
인스턴스에 있을 수 있다. 여러 대로 운영하기 전에는 브로커 릴레이나 별도의 분산
전달 구조가 필요하다.
