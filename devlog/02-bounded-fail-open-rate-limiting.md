# Redis 장애 때 요청을 통과시키되 오래 기다리지 않기

여러 게이트웨이 인스턴스가 같은 호출 제한을 적용하려면 토큰 버킷 상태를 공유해야
한다. 이 저장소는 Bucket4j 버킷을 Redis에 저장한다. Redis가 멈췄을 때
게이트웨이까지 멈출지, 제한 없이 요청을 통과시킬지 선택해야 한다.

## 버킷 키

[`RateLimitKeyResolver`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimitKeyResolver.java)는
검증한 JWT에 비어 있지 않은 `sub`가 있으면 `user:{subject}`로, 그 밖에는 요청
주소를 `ip:{address}`로 만든다. 사용자 한도가 IP 한도보다 큰 이유는 NAT 뒤의
여러 사용자를 하나로 묶지 않기 위해서다.

익명 주소는 `X-Forwarded-For` 첫 값을 사용한다. 이 헤더를 외부가 그대로 만들 수
있으면 요청마다 값을 바꿔 IP 제한을 우회할 수 있다. 앞단 프록시가 헤더를
덮어쓰고 게이트웨이로 직접 접근하지 못하게 막는 구성이 호출 제한의 일부다.

## 제한 처리 예외에서는 요청을 통과시킨다

Redis 장애가 betting·wallet 조회 전체 장애가 되지 않도록
[`RateLimiterService`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimiterService.java)는
버킷 구성과 Redis 소비 중 생긴 모든 `RuntimeException`에서 요청을 허용한다.

```java
try {
  BucketConfiguration configuration =
      BucketConfiguration.builder()
          .addLimit(
              bandwidth ->
                  bandwidth
                      .capacity(limit.capacity())
                      .refillGreedy(limit.capacity(), limit.refillPeriod()))
          .build();
  BucketProxy bucket =
      proxyManager(limit.refillPeriod())
          .builder()
          .build(key.getBytes(StandardCharsets.UTF_8), () -> configuration);

  ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
  if (probe.isConsumed()) {
    return RateLimitResult.allow(probe.getRemainingTokens());
  }
  return RateLimitResult.deny(secondsToWait(probe.getNanosToWaitForRefill()));
} catch (RuntimeException e) {
  log.warn(
      "Rate limiter degraded, failing open for key prefix '{}': {}",
      keyPrefix(key),
      e.toString());
  return RateLimitResult.failedOpen();
}
```

실패한 경우 버킷에 남은 토큰 수를 알 수 없으므로 `X-RateLimit-Remaining`을 보내지
않는다. 정상적으로 한도를 넘긴 경우에는 429 Problem Detail,
`Retry-After`, `X-RateLimit-Remaining: 0`을 반환한다.

이 범위에는 Redis 장애뿐 아니라 잘못된 용량·충전 주기 같은 로컬 구성 오류도
포함된다. 호출 제한 실패 지표는 없고 경고 로그만 남으므로, 설정 오류가 요청마다
통과로 숨을 수 있다. 장애 동안 남용을 막으려면 상위 WAF나 인프라 게이트웨이에
별도 한도가 있어야 한다.

## 예외를 잡는 것만으로는 부족하다

연결되지 않은 Redis는 빠르게 연결 거부를 반환하지만, 이미 연결된 Redis가
멈추면 명령이 오래 대기할 수 있다. 예외 처리만 추가하면 요청 스레드가 제한 시간
동안 쌓여 사실상 게이트웨이 장애가 된다.

[`RateLimitConfig`](../src/main/java/com/sportsbook/gateway/ratelimit/RateLimitConfig.java)는
다음 값을 함께 설정한다.

- 소켓 연결 제한 시간 300ms
- 명령 제한 시간 500ms
- 연결이 끊긴 상태의 명령 즉시 거부
- 자동 재연결 유지

Redis 클라이언트는 애플리케이션 시작 시점이 아니라 처음 요청이 왔을 때 연결을
만든다. 시작 자체는 Redis 가용성에 묶이지 않고, 복구 후에는 같은 클라이언트가
자동으로 제한을 다시 적용한다.

[`RateLimitTest`](../src/test/java/com/sportsbook/gateway/ratelimit/RateLimitTest.java)는
정상 Redis에 먼저 연결한 뒤 컨테이너를 일시 정지한다. 호출 제한이
2초 안에 `failOpen=true`로 돌아오는지, 컨테이너를 재개한 뒤 5초 안에 정상 모드로
복귀하는지 확인한다. 닫힌 포트만 시험하면 이 장애 유형을 잡지 못한다.

## 버킷 만료 정책의 주의점

`RateLimiterService`는 첫 호출 때 하나의 `ProxyManager`를 만들며, 그때 받은
토큰 충전 주기로 Redis 버킷 만료 전략을 정한다. 기본 사용자·IP 주기는 모두 1분이라
문제가 없지만 둘을 서로 다르게 설정하면 먼저 생성된 버킷 종류의 기간이 공통
만료 계산에 쓰인다. 기간을 분리하려면 `ProxyManager`도 정책별로 나누거나 공통
만료 전략을 명시해야 한다.

## WebSocket도 한도를 사용한다

상태 확인과 `/error`만 호출 제한에서 제외한다. `/ws/**` 핸드셰이크도 비어 있지
않은 `sub`가 있으면 사용자 버킷, 그 밖에는 IP 버킷을 소비한다. 핸드셰이크를
제한하지 않으면 STOMP 구독 권한이 안전해도 연결 생성 자체로 자원을 소진시킬 수
있다.
