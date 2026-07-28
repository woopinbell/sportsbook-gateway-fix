# Kafka와 STOMP 전달

실시간 경로는 Kafka consumer와 WebSocket session 사이의 변환 계층입니다. 공개 배당과
사용자별 베팅 상태를 같은 broker에서 처리하지만 인증 범위와 destination은 분리합니다.

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

Kafka 값은 byte array이며 `shared-protocol`이 생성한 SpecificRecord로 해석합니다.
외부 JSON에는 필요한 필드만 담은 `OddsUpdate`, `BetStatusUpdate`를 사용합니다.
Avro generated 객체를 그대로 노출하지 않아 schema 세부와 WebSocket 응답을 분리합니다.

## 연결과 권한

| 단계 | 공개 배당 | 사용자 베팅 상태 |
|---|---|---|
| HTTP handshake | 익명 허용 | 익명 연결 자체는 가능 |
| STOMP CONNECT | 토큰 선택 | bearer token 필요 |
| SUBSCRIBE | `/topic/odds/{eventId}` | `/user/queue/bets`와 principal 필요 |
| SEND | 거절 | 거절 |

인증은 CONNECT frame에서 수행하므로 HTTP Authorization header만 넣고 STOMP native
header를 빠뜨리면 사용자 session이 되지 않습니다.

## 단일 인스턴스 제약

Spring simple broker의 사용자 session 정보는 JVM 안에만 있습니다. Kafka consumer
group은 한 event를 한 gateway instance에 전달합니다. gateway가 여러 대이면 event를
받은 instance와 사용자 session을 가진 instance가 다를 수 있습니다.

다중 인스턴스 선택지는 다음과 같습니다.

- STOMP broker relay로 session과 destination을 외부 broker에 위임
- Kafka event를 모든 gateway instance에 복제하는 fan-out 계층
- 사용자 session 소유권에 맞춰 event를 라우팅하는 별도 gateway

현재 코드는 첫 번째 확장 전 단계인 단일 인스턴스용입니다.

## 실패와 관측

| 실패 | 현재 결과 | 확인할 신호 |
|---|---|---|
| Kafka 연결 | 실시간 갱신 중단 | consumer health와 lag |
| 잘못된 Avro payload | listener 예외 | decode 오류 로그 |
| 잘못된 토픽 이름 | 메시지 수신 없음 | topic별 consumer offset |
| 느린 WebSocket client | buffer/time limit 초과 | session 종료와 전송 오류 |
| 허용되지 않은 frame | `MessageDeliveryException` | STOMP 오류와 보안 로그 |

dead-letter topic이 없으므로 같은 잘못된 record가 consumer 진행을 계속 막는지 운영
환경에서 확인해야 합니다. payload 오류를 단순 연결 장애와 같은 경보로 묶으면 원인
분리가 늦어집니다.

구현 상세와 테스트 방법은
[Kafka에서 STOMP로 전달하는 경계](../devlog/03-kafka-to-stomp-access-control.md)에
있습니다.
