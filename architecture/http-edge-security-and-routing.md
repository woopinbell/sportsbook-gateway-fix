# HTTP 보안과 라우팅

게이트웨이는 외부 신원을 검증하고 내부 서비스가 신뢰할 수 있는 요청으로 바꾸는
경계입니다. 인증, 호출 제한, 신원 전달, 경로 변환은 순서가 바뀌면 의미도 달라집니다.

## 요청 흐름

```text
client
  │
  ▼
TrustedHeaderFilter
  │  외부 X-User-Id / X-User-Roles 제거
  ▼
Spring Security
  │  공개 조회는 익명, 나머지는 JWT 검증
  ▼
RateLimitFilter
  │  인증 사용자는 subject, 익명은 IP bucket
  ▼
GatewayRoutes
  ├─ 검증된 X-User-* 추가
  ├─ traceparent 유지 또는 생성
  ├─ path/query 변환
  └─ 하위 서비스로 proxy
```

상태 확인 경로는 호출 제한에서 제외합니다. WebSocket handshake는 HTTP 보안에서
허용하지만 IP bucket은 소비하며, frame 권한은 STOMP interceptor가 맡습니다.

## 라우팅 표

| 외부 경로 | 내부 대상 | 변환 |
|---|---|---|
| `/api/v1/bets`, `/api/v1/bets/**` | betting-service | `/internal/v1/bets...`, 목록 `userId` 강제 |
| `/api/v1/wallet/balance` | wallet-service | `/internal/v1/wallet/accounts/{sub}/balance` |
| `/api/v1/events`, `/api/v1/events/**` | odds-feed-service | path 유지 |
| `/api/v1/odds/**` | odds-feed-service | path 유지 |

bet과 wallet은 인증이 필요합니다. events와 odds 조회는 공개입니다. 보안 설정에서
허용한 `/api/v1/odds` 정확한 경로는 router에 없으므로 실제 공개 계약은
`/api/v1/odds/**`입니다.

## 신원과 사용자 입력

검증된 subject는 header와 필요한 path/query에 넣습니다. 외부 헤더는 먼저 제거하고
query는 `set`으로 교체합니다. POST body는 gateway가 해석하지 않으므로 하위 서비스가
본문 사용자와 header 사용자를 다시 비교합니다.

이중 검사의 목적은 중복이 아닙니다. gateway는 전송 경계를 보호하고, 소유 서비스는
업무 명령의 불변식을 보호합니다.

## 호출 제한의 장애 정책

Redis가 정상일 때 bucket은 여러 gateway가 공유합니다. Redis가 없거나 응답하지
않으면 요청을 허용하고 남은 token header를 생략합니다. 연결·명령 제한시간으로
장애 경로가 짧게 끝나도록 했지만, 그동안 호출 제한은 적용되지 않습니다.

운영 배포에는 다음 보완이 필요합니다.

- 신뢰 ingress의 forwarded header 정리
- WAF 또는 외부 gateway의 최소 한도
- Redis 장애와 fail-open 비율 경보
- Redis 복구 뒤 정상 제한 재개 확인

## 응답과 trace

proxy는 하위 응답을 바꾸지 않으므로 RFC 7807 상태와 본문이 그대로 돌아갑니다.
들어온 `traceparent`도 유지합니다. trace context는 관측 정보이며 신뢰할 수 있는
사용자 식별자가 아닙니다.

## 함께 읽을 문서

- [신뢰할 수 있는 사용자 전달과 경로 변환](../devlog/01-trusted-identity-and-route-rewrite.md)
- [제한시간이 있는 장애 시 허용 호출 제한](../devlog/02-bounded-fail-open-rate-limiting.md)
- [Kafka와 STOMP 전달](kafka-to-stomp-delivery.md)
