# 게이트웨이 개발 기록

외부 헤더와 JWT를 먼저 정리하고, 다음 커밋에서 Redis 호출 제한을 붙였다. 내부
서비스 라우팅과 신원 전달은 그 뒤에 들어왔고, 마지막 기능 단계에서 Kafka 이벤트를
WebSocket으로 보냈다. Redis 장애 시 대기 시간을 제한한 수정과 STOMP 명령 허용
범위를 축소한 수정은 각각의 기록 안에 이어서 적었다.

1. [외부 신뢰 헤더를 지우고 JWT를 검증하기](01-trusted-header-and-jwt-boundary.md)
2. [Redis 장애 때 요청을 통과시키되 오래 기다리지 않기](02-bounded-fail-open-rate-limiting.md)
3. [내부 경로를 바꾸고 검증된 신원을 전달하기](03-route-rewrite-and-identity-forwarding.md)
4. [Kafka 이벤트를 STOMP 구독자에게 나눠 보내기](04-kafka-to-stomp-access-control.md)

현재 HTTP 경계는 [HTTP 보안과 라우팅](../architecture/http-edge-security-and-routing.md),
실시간 경계는 [Kafka와 STOMP 전달](../architecture/kafka-to-stomp-delivery.md)에
연결해 두었다.

기능 테스트와 부하 측정은 목적이 다르다. `./mvnw verify`는 보안·라우팅·장애
처리 계약을 검사하고, 처리량과 동시 연결 수는 [`load-test/`](../load-test/README.md)의
별도 시나리오로 측정한다.
