# Kafka와 STOMP 전달

실시간 경로는 Kafka 이벤트를 WebSocket 세션에 맞는 메시지로 바꾼다. 공개 배당과
사용자별 베팅 상태가 같은 단순 브로커를 지나지만 구독 주소와 인증 범위는 분리된다.

## 전달 흐름

```text
odds-feed ── odds.changed ──► OddsStreamListener
                                  │
                                  └─► /topic/odds/{eventId}

settlement ─ bet.settled.v1 ─┐
                             ├─► BetStatusStreamListener
settlement ─ bet.voided.v1 ──┘         │
                                       └─► /user/queue/bets
                                            principal == event.userId
```

Kafka 값은 바이트 배열이다.
[`AvroDecoder`](../src/main/java/com/sportsbook/gateway/ws/AvroDecoder.java)가
`shared-protocol`에서 생성한 레코드로 해석하고,
[`OddsStreamListener`](../src/main/java/com/sportsbook/gateway/ws/OddsStreamListener.java)와
[`BetStatusStreamListener`](../src/main/java/com/sportsbook/gateway/ws/BetStatusStreamListener.java)가
각각 `OddsUpdate`, `BetStatusUpdate` JSON으로 보낸다. Avro 레코드 자체는 외부
응답에 노출되지 않는다.

## 연결과 권한

| 단계 | 토큰과 사용자 | 허용 범위 |
|---|---|---|
| HTTP 핸드셰이크 | 토큰이 없으면 익명, 유효하면 JWT subject를 principal로 설정 | 익명 연결은 허용하지만 잘못된 토큰은 401 |
| STOMP CONNECT | 프레임에 토큰이 없으면 핸드셰이크 principal 유지, 있으면 그 subject로 교체 | 잘못된 토큰은 연결 거절 |
| SUBSCRIBE | principal 선택을 바꾸지 않음 | `/topic/odds/` 뒤에 값이 있는 주소, 또는 인증된 `/user/queue/bets` |
| SEND | principal과 무관 | 모두 거절 |

HTTP와 CONNECT에 서로 다른 유효한 토큰이 함께 오면 CONNECT 프레임에 넣은 토큰의
subject가 최종 principal이 된다.
[`StompAuthChannelInterceptor`](../src/main/java/com/sportsbook/gateway/ws/StompAuthChannelInterceptor.java)는
배당 구독 주소의 접두사와 비어 있지 않은 뒷부분만 확인한다. UUID 형식이나 경로
조각 수까지 검사하지는 않는다.

## 실행 단위와 실패 경계

단순 브로커의 세션 정보는 JVM 안에만 있다. Kafka 소비자 그룹은 한 레코드를 그룹
안의 한 게이트웨이에만 전달하므로, 여러 인스턴스를 띄우면 이벤트를 받은 곳과
사용자 세션이 열린 곳이 다를 수 있다. 현재 전달 구조의 실행 단위는 게이트웨이
한 대다.

| 실패 | 현재 결과 | 확인할 신호 |
|---|---|---|
| Kafka 연결 | 실시간 갱신 중단 | 소비자 상태와 지연 |
| 잘못된 Avro 레코드 | 최초 처리와 9회 재시도 뒤 로그만 남기고 다음 레코드로 진행 | Avro 해석 오류 로그 |
| 잘못된 토픽 이름 | 메시지 수신 없음 | 토픽별 소비자 오프셋 |
| 느린 WebSocket 클라이언트 | 버퍼·전송 제한 초과 | 세션 종료와 전송 오류 |
| 허용되지 않은 프레임 | `MessageDeliveryException` | STOMP 오류와 보안 로그 |

별도 오류 처리기와 실패 레코드 보관 토픽(DLT)이 없어 기본 복구 뒤 잘못된
레코드의 실시간 갱신은 유실된다. 소비자는 멈추지 않고 다음 레코드를 처리한다.

인증과 기본 Kafka 오류 처리의 세부 내용은
[Kafka 이벤트를 STOMP 구독자에게 나눠 보내기](../devlog/04-kafka-to-stomp-access-control.md)에
있다.
