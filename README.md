# 게이트웨이

`gateway`는 스포츠북 시스템의 외부 요청을 받는 단일 진입점입니다. REST와
WebSocket 요청의 인증, 호출 제한, 라우팅, 실시간 메시지 전달을 맡고 도메인 처리는
내부 서비스에 위임합니다.

## 요청 처리

### 인증

Spring Security OAuth2 Resource Server가 RS256 JWT를 검증합니다. 외부 요청에
포함된 `X-User-Id`와 `X-User-Roles`는 먼저 제거하고, 검증한 토큰의 사용자와 역할을
내부 요청에 다시 넣습니다. 클라이언트가 신뢰 헤더를 직접 만들어 다른 사용자로
행동할 수 없도록 하는 경계입니다.

### 호출 제한

Bucket4j의 토큰 버킷을 Redis에 저장합니다. 인증한 요청은 사용자별로, 익명 요청은
IP별로 한도를 계산하므로 게이트웨이 인스턴스가 여러 대여도 같은 제한을 공유합니다.
Redis를 사용할 수 없을 때는 게이트웨이 전체가 중단되지 않도록 요청을 허용합니다.
연결 시도는 300ms, Redis 명령은 500ms로 제한하고 재연결 중 명령을 즉시 거절하므로
장애 시 허용 경로가 Redis의 장시간 응답 대기에 묶이지 않습니다. 자동 재연결은 유지되어
Redis가 복구되면 분산 호출 제한을 다시 적용합니다. 따라서 장애 시간에는 호출 제한이
강제되지 않는 가용성 우선(장애 시 허용) 정책입니다.

익명 요청의 IP는 `X-Forwarded-For` 첫 번째 값에서 읽습니다. 외부 클라이언트가
게이트웨이에 직접 연결할 수 없고 신뢰할 수 있는 ingress가 이 헤더를 덮어쓰는
환경에서만 올바른 사용자별 IP 제한이 됩니다.

### REST 라우팅

등록된 공개 REST 경로만 내부 서비스로 전달합니다.

- `/api/v1/bets`, `/api/v1/bets/**`: `betting-service`
- `GET /api/v1/wallet/balance`: `wallet-service`
- `/api/v1/events`, `/api/v1/events/**`, `/api/v1/odds/**`:
  `odds-feed-service`

베팅 경로는 `/internal/v1/bets` 아래로, 잔액 조회는 인증한 사용자의
`/internal/v1/wallet/accounts/{userId}/balance`로 바꿉니다. 경기와 배당 조회는
공개 경로를 그대로 사용합니다. 인증이 필요한 경로에는 검증된 사용자 정보를,
모든 REST 경로에는 W3C `traceparent`를 전달합니다. 하위 서비스의 RFC 7807 오류
응답은 본문을 바꾸지 않고 반환합니다.

### 실시간 메시지

Kafka에서 배당 변경과 베팅 정산 이벤트를 받아 STOMP 구독자에게 전달합니다.

- `/ws/v1/odds`: 공개 배당 변경
- `/ws/v1/bets`: 인증한 사용자의 베팅 상태
- `/topic/odds/{eventId}`: 이벤트별 배당 방송
- `/user/queue/bets`: 사용자별 정산 결과

`bet.settled.v1`과 `bet.voided.v1`을 구독하며, 베팅 상태는 해당 사용자의 세션에만
보냅니다. 게이트웨이는 서버→클라이언트 분배 전용이므로 클라이언트 `SEND` 프레임을
모두 거절합니다. 구독도 `/topic/odds/{eventId}`와 인증된 `/user/queue/bets`만
허용하며, 브라우저 출처는 `GATEWAY_WS_ALLOWED_ORIGINS`로 명시해야 합니다.

## 기술 구성

- Java 17, Spring Boot 3.2.11, Maven
- Spring Cloud Gateway Server MVC 2023.0.3
- Spring Security OAuth2 Resource Server
- Spring WebSocket, STOMP
- Bucket4j, Redis
- Kafka, Avro
- Micrometer, OpenTelemetry, Prometheus

Spring의 내장 STOMP 브로커는 서블릿 스택을 사용합니다. 같은 애플리케이션에서
라우팅과 STOMP를 함께 운영하기 위해 WebFlux 기반 게이트웨이 대신 Gateway Server
MVC를 선택했습니다.

## 빌드와 검증

`shared-protocol` 0.3.0을 로컬 Maven 저장소에 먼저 설치해야 합니다. 통합 테스트는
Redis Testcontainer와 Embedded Kafka를 사용하므로 Docker가 실행 중이어야 합니다.

```sh
cd ../sportsbook-shared-protocol
./mvnw install

cd ../sportsbook-gateway
./mvnw verify
./mvnw spring-boot:run
```

기본 HTTP 포트는 `8080`입니다. `@Tag("load")`가 붙은 부하 테스트는 일반
`verify`에서 제외되며 다음 명령으로 따로 실행합니다.

```sh
./mvnw test -Dsurefire.excludedGroups= -Dtest=GatewayLoadTest
```

## 공개 인터페이스

- 베팅: `/api/v1/bets`, `/api/v1/bets/**`
- 지갑 잔액: `/api/v1/wallet/balance`
- 공개 경기·배당 조회: `/api/v1/events`, `/api/v1/events/**`, `/api/v1/odds/**`
- WebSocket: `/ws/v1/odds`, `/ws/v1/bets`
- 상태 확인: `/actuator/health/liveness`, `/actuator/health/readiness`
- Prometheus: `/actuator/prometheus`

## 성능 측정 상태

현재 소스로 WebSocket 메시지 분배나 REST 라우팅 처리량을 재현한 측정 결과가
없습니다. 따라서 동시 연결 수, RPS, p99 또는 오류율을 현재 성능 수치로 제시하지
않습니다.

라우팅·인증·Redis 장애 시 허용과 Kafka-to-STOMP 전달의 기능은 테스트로 검증합니다.
`load-test/results/<날짜>/`의 결과는 단일 JVM 개발 환경에서 만든 참고 자료이며
현재 코드의 대표 수치가 아닙니다. 실행 방법과 자료 범위는
[부하 테스트 문서](load-test/README.md)에서 확인할 수 있습니다.

## 현재 제한

- 이 저장소에는 JWT 발급, 갱신, 폐기 목록이 없습니다. 공개키와 시간 조건을 통과한
  토큰은 만료될 때까지 유효하므로 발급·폐기 정책은 외부 IAM에서 관리해야 합니다.
- STOMP 단순 브로커는 단일 인스턴스 구성입니다. 여러 인스턴스에서 메시지를
  공유하려면 브로커 릴레이나 별도의 분산 전달 계층이 필요합니다.
- Redis 장애 중에는 요청을 허용하므로 호출 제한만으로 남용을 막을 수
  없습니다. 운영 환경에서는 상위 WAF 또는 API 게이트웨이 한도를 함께 사용해야 합니다.
- 게이트웨이는 요청 본문을 다시 작성하지 않습니다. `betting-service`가
  `X-User-Id`와 본문 또는 쿼리의 사용자 일치를 최종 확인합니다.
- Kafka payload를 Avro로 해석하지 못하면 listener가 실패하며 이 저장소에는 별도의
  dead-letter topic 설정이 없습니다.

구현 과정과 장애 대응은 [`devlog/`](devlog/README.md), 현재 HTTP·실시간 전달 구조는
[`architecture/`](architecture/http-edge-security-and-routing.md)에 정리되어 있습니다.
