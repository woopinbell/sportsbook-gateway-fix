# gateway

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
IP별로 한도를 계산하므로 gateway 인스턴스가 여러 대여도 같은 제한을 공유합니다.
Redis를 사용할 수 없을 때는 gateway 전체가 중단되지 않도록 요청을 허용합니다.

### REST 라우팅

공개 `/api/v1/*` 요청을 내부 서비스의 계약에 맞게 전달합니다.

- 베팅 접수와 조회: `betting-service`
- 잔액 조회: `wallet-service`
- 이벤트와 배당 조회: `odds-feed-service`

경로를 `/internal/v1/*`로 바꾸고 검증된 사용자 정보와 W3C `traceparent`를
전달합니다. 하위 서비스의 RFC 7807 오류 응답은 본문을 바꾸지 않고 반환합니다.

### 실시간 메시지

Kafka에서 배당 변경과 베팅 정산 이벤트를 받아 STOMP 구독자에게 전달합니다.

- `/ws/v1/odds`: 공개 배당 변경
- `/ws/v1/bets`: 인증한 사용자의 베팅 상태
- `/topic/odds/{eventId}`: 이벤트별 배당 방송
- `/user/queue/bets`: 사용자별 정산 결과

`bet.settled.v1`과 `bet.voided.v1`을 구독하며, 베팅 상태는 해당 사용자의 세션에만
보냅니다.

## 기술 구성

- Java 17, Spring Boot 3.2.11, Maven
- Spring Cloud Gateway Server MVC 2023.0.3
- Spring Security OAuth2 Resource Server
- Spring WebSocket, STOMP
- Bucket4j, Redis
- Kafka, Avro
- Micrometer, OpenTelemetry, Prometheus

Spring의 내장 STOMP 브로커는 서블릿 스택을 사용합니다. 같은 애플리케이션에서
라우팅과 STOMP를 함께 운영하기 위해 WebFlux 기반 gateway 대신 Gateway Server
MVC를 선택했습니다.

## 빌드와 검증

`shared-protocol` 0.2.0을 로컬 Maven 저장소에 먼저 설치해야 합니다. 통합 테스트는
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

- HTTP: `/api/v1/*`
- WebSocket: `/ws/v1/odds`, `/ws/v1/bets`
- 상태 확인: `/actuator/health/liveness`, `/actuator/health/readiness`
- Prometheus: `/actuator/prometheus`

## 성능 확인

2026년 5월 30일 개발 환경에서 500개 STOMP 구독자가 한 번의 배당 변경을 모두
수신했고 최대 지연은 약 210ms였습니다. 같은 환경의 REST 라우팅은 약 2.6k RPS,
p99 약 30~43ms, 오류율 0.2~0.6%를 기록했습니다.

이 값은 gateway와 WireMock을 한 JVM에서 실행한 기준입니다. 1만 동시 연결 목표와
분리된 호스트 환경을 대신하지 않습니다. 실행 방법과 상세 수치는
[부하 테스트 결과](load-test/results/BEST.md)에서 확인할 수 있습니다.

## 현재 제한

- JWT 폐기는 블랙리스트 대신 짧은 만료 시간과 refresh token으로 처리합니다.
- STOMP simple broker는 단일 인스턴스 구성입니다. 여러 인스턴스에서 메시지를
  공유하려면 broker relay나 별도의 분산 전달 계층이 필요합니다.
- gateway는 요청 본문을 다시 작성하지 않습니다. `betting-service`가
  `X-User-Id`와 본문 또는 쿼리의 사용자 일치를 최종 확인합니다.
