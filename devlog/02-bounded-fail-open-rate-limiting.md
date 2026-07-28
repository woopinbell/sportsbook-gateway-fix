# 제한시간이 있는 장애 시 허용 호출 제한

여러 gateway 인스턴스가 같은 호출 제한을 적용하려면 token bucket 상태를 공유해야
합니다. 이 저장소는 Bucket4j bucket을 Redis에 저장합니다. Redis가 멈췄을 때
게이트웨이까지 멈출지, 제한 없이 요청을 통과시킬지 선택해야 합니다.

## 버킷 키

`RateLimitKeyResolver`는 인증된 JWT subject를 `user:{subject}`로, 익명 요청의
주소를 `ip:{address}`로 만듭니다. 사용자 한도가 IP 한도보다 큰 이유는 NAT 뒤의
여러 사용자를 하나로 묶지 않기 위해서입니다.

익명 주소는 `X-Forwarded-For` 첫 값을 사용합니다. 이 헤더를 외부가 그대로 만들 수
있으면 요청마다 값을 바꿔 IP 제한을 우회할 수 있습니다. ingress가 헤더를
덮어쓰고 gateway 직접 접근을 막는 구성이 호출 제한의 일부입니다.

## 장애 시 허용을 선택한 이유

Redis 장애가 betting·wallet 조회 전체 장애가 되지 않도록 `RateLimiterService`는
Redis 예외에서 요청을 허용합니다.

```java
try {
  ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
  return probe.isConsumed()
      ? RateLimitResult.allow(probe.getRemainingTokens())
      : RateLimitResult.deny(secondsToWait(probe.getNanosToWaitForRefill()));
} catch (RuntimeException e) {
  return RateLimitResult.failedOpen();
}
```

실패한 경우 남은 token 수를 알 수 없으므로 `X-RateLimit-Remaining`을 보내지
않습니다. 정상적으로 한도를 넘긴 경우에는 429 Problem Detail,
`Retry-After`, `X-RateLimit-Remaining: 0`을 반환합니다.

장애 시 허용은 가용성 선택이지 보안 기능 유지가 아닙니다. Redis 장애 동안 남용을
막으려면 상위 WAF나 인프라 gateway에 별도 한도가 있어야 합니다.

## 예외를 잡는 것만으로는 부족하다

연결되지 않은 Redis는 빠르게 connection refused를 반환하지만, 이미 연결된 Redis가
멈추면 명령이 오래 대기할 수 있습니다. 예외 처리만 추가하면 요청 thread가 timeout
동안 쌓여 사실상 gateway 장애가 됩니다.

`RateLimitConfig`는 다음 값을 함께 설정합니다.

- socket connect timeout 300ms
- command timeout 500ms
- disconnected 상태의 명령 즉시 거부
- 자동 재연결 유지

Redis client는 첫 요청에서 느리게 연결합니다. 애플리케이션 시작 자체는 Redis
가용성에 묶이지 않고, 복구 후에는 같은 client가 자동으로 제한을 다시 적용합니다.

`RateLimitTest`는 정상 Redis로 연결을 데운 뒤 container를 pause합니다. 호출 제한이
2초 안에 `failOpen=true`로 돌아오는지, unpause 뒤 5초 안에 정상 모드로 복귀하는지
확인합니다. 닫힌 port만 시험하면 이 장애 유형을 잡지 못합니다.

## bucket 만료 정책의 주의점

현재 `RateLimiterService`는 첫 호출 때 하나의 proxy manager를 만들며, 그때 받은
refill period로 Redis bucket 만료 전략을 정합니다. 기본 user·IP 기간은 모두 1분이라
문제가 없지만 둘을 서로 다르게 설정하면 먼저 생성된 bucket 종류의 기간이 공통
만료 계산에 쓰입니다. 기간을 분리하려면 proxy manager도 정책별로 나누거나 공통
만료 전략을 명시해야 합니다.

## WebSocket도 한도를 사용한다

상태 확인과 `/error`만 호출 제한에서 제외합니다. `/ws/**` handshake는 익명 IP
bucket을 소비합니다. handshake를 무료로 두면 STOMP destination 권한이 안전해도
연결 생성 자체로 자원을 소진시킬 수 있습니다.

```sh
./mvnw -Dtest=RateLimitTest,RateLimiterServiceTest,RateLimitKeyResolverTest test
```
