# 게이트웨이 개발 기록

외부 요청을 내부 서비스와 실시간 채널로 전달하면서 생긴 경계 문제를 세 묶음으로
정리했습니다.

| 문제 | 문서 |
|---|---|
| 클라이언트가 위조한 사용자 정보와 쿼리를 내부로 보내지 않는 방법 | [신뢰할 수 있는 사용자 전달과 경로 변환](01-trusted-identity-and-route-rewrite.md) |
| Redis 장애가 게이트웨이 지연으로 번지지 않게 하는 방법 | [제한시간이 있는 장애 시 허용 호출 제한](02-bounded-fail-open-rate-limiting.md) |
| Kafka 이벤트를 허용된 STOMP 구독자에게만 보내는 방법 | [Kafka에서 STOMP로 전달하는 경계](03-kafka-to-stomp-access-control.md) |

현재 구조는 다음 문서에서 한눈에 볼 수 있습니다.

- [HTTP 보안과 라우팅](../architecture/http-edge-security-and-routing.md)
- [Kafka와 STOMP 전달](../architecture/kafka-to-stomp-delivery.md)

기능 테스트와 부하 측정은 목적이 다릅니다. `./mvnw verify`는 보안·라우팅·장애
처리 계약을 검사하고, 처리량과 동시 연결 수는 [`load-test/`](../load-test/README.md)의
별도 시나리오로 측정합니다.
