# HTTP 보안과 라우팅

게이트웨이는 외부 신원을 검증하고 내부 서비스가 신뢰할 수 있는 요청으로 바꾸는
경계다. 인증, 호출 제한, 신원 전달, 경로 변환은 순서가 바뀌면 의미도 달라진다.

## 요청 흐름

```text
클라이언트
  │
  ▼
TrustedHeaderFilter
  │  외부 X-User-Id / X-User-Roles 제거
  ▼
Spring Security
  │  공개 조회는 익명, 나머지는 JWT 검증
  ▼
RateLimitFilter
  │  비어 있지 않은 sub가 있으면 사용자, 그 밖에는 IP 버킷
  ▼
GatewayRoutes
  ├─ 검증된 X-User-* 추가
  ├─ traceparent 유지 또는 생성
  ├─ 경로·쿼리 변환
  └─ 하위 서비스로 전달
```

상태 확인 경로는 호출 제한에서 제외한다. WebSocket 핸드셰이크도 호출 제한을
거친다. HTTP 인증에 비어 있지 않은 `sub`가 있으면 사용자 버킷, 그 밖에는 IP
버킷을 소비하며 STOMP 프레임 권한은 채널 인터셉터가 맡는다.

각 단계의 구현은
[`TrustedHeaderFilter`](../src/main/java/com/sportsbook/gateway/security/TrustedHeaderFilter.java),
[`SecurityConfig`](../src/main/java/com/sportsbook/gateway/security/SecurityConfig.java),
[`RateLimitFilter`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimitFilter.java),
[`GatewayRoutes`](../src/main/java/com/sportsbook/gateway/routing/GatewayRoutes.java)에 있다.
신원과 추적 정보를 만드는 세부 책임은
[`IdentityForwarding`](../src/main/java/com/sportsbook/gateway/routing/IdentityForwarding.java)과
[`TraceForwarding`](../src/main/java/com/sportsbook/gateway/routing/TraceForwarding.java)이
나눠 가진다.

## 라우팅 표

| 외부 경로 | 내부 대상 | 변환 |
|---|---|---|
| `/api/v1/bets`, `/api/v1/bets/**` | betting-service | `/internal/v1/bets...`, 목록 `userId` 강제 |
| `/api/v1/wallet/balance` | wallet-service | `/internal/v1/wallet/accounts/{sub}/balance` |
| `/api/v1/events`, `/api/v1/events/**` | odds-feed-service | 경로 유지 |
| `/api/v1/odds/**` | odds-feed-service | 경로 유지 |

베팅과 지갑 경로는 인증이 필요하고 경기와 배당 조회는 공개다. Spring
`PathPattern`의 `/**`는 남은 경로가 없는 경우도 포함하므로 `/api/v1/odds/**`
route는 `/api/v1/odds` 자체도 받는다.
공개 권한은 경기·배당의 `GET`에만 적용되지만 `oddsFeedReadRoute()` 자체에는 HTTP
메서드 조건이 없다. 따라서 인증한 요청의 POST·PUT·DELETE도 같은 경로라면 하위
서비스로 전달된다. 현재 하위 API는 이를 거절하지만, 공개 경로에 상태 변경 API를
추가할 때는 게이트웨이 route에도 메서드 조건을 넣어야 한다.

## 신원과 사용자 입력

검증된 JWT의 `sub` 클레임은 내부 헤더와 필요한 경로·쿼리에 넣는다. 외부 신원 헤더는 먼저
제거하고 베팅 목록의 `userId` 쿼리는 `set`으로 교체한다. POST 본문은 게이트웨이가
해석하지 않으므로 하위 서비스가 본문의 사용자와 내부 헤더를 다시 비교한다.

현재 `JwtDecoder`는 issuer와 audience를 검사하지 않고 `sub`와 `exp` 클레임의 존재도
요구하지 않는다. `sub` 클레임이 없는 서명 토큰은 인증 자체는 통과하지만 IP
버킷을 사용하고, 신원 헤더·목록 `userId` 강제·지갑 잔액 경로 변환이 빠진다.
베팅 경로 자체의 외부→내부 변환은 그대로 수행된다. `exp`가 없으면 만료 시각도
검사할 수 없다.
보호 경로가 `authenticated`라는 사실만으로 완전한 사용자 신원과 수명을 보장하지
않는다. 운영 환경에서는 issuer·audience·`sub`·`exp`를 검증하는 별도 검증기가
필요하다.

## 호출 제한의 장애 정책

Redis가 정상일 때 버킷은 여러 게이트웨이가 공유한다. 버킷 구성이나 Redis 소비에서
`RuntimeException`이 발생하면 요청을 허용하고 남은 토큰 수 헤더를 생략한다. 연결은
300ms, 명령은 500ms까지만 기다린다. Redis 장애뿐 아니라 잘못된 용량·충전 주기 같은
구성 오류도 같은 통과 경로로 가며 별도 실패 지표 없이 경고 로그만 남는다.

키 선택은
[`RateLimitKeyResolver`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimitKeyResolver.java),
버킷 소비와 장애 시 요청 통과는
[`RateLimiterService`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimiterService.java),
Lettuce 대기 시간은
[`RateLimitConfig`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimitConfig.java)에
있다.

## 응답과 추적 정보

게이트웨이는 하위 응답을 바꾸지 않으므로 RFC 7807 상태와 본문이 그대로 돌아간다.
들어온 `traceparent`도 유지한다. 추적 정보는 관측에만 쓰며 사용자 신원으로
사용하지 않는다.

## 함께 읽을 문서

- [외부 신뢰 헤더를 지우고 JWT를 검증하기](../devlog/01-trusted-header-and-jwt-boundary.md)
- [Redis 장애 때 요청을 통과시키되 오래 기다리지 않기](../devlog/02-bounded-fail-open-rate-limiting.md)
- [내부 경로를 바꾸고 검증된 신원을 전달하기](../devlog/03-route-rewrite-and-identity-forwarding.md)
- [Kafka와 STOMP 전달](kafka-to-stomp-delivery.md)
