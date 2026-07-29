# 내부 경로를 바꾸고 검증된 신원을 전달하기

인증을 통과한 요청을 단순 프록시하면 외부 API 모양과 내부 서비스 계약이 그대로
결합된다. 베팅 목록에는 클라이언트가 만든 `userId`가 남고, 지갑 잔액 경로에도
다른 사용자의 ID를 넣을 자리가 생긴다. 라우팅 단계에서 검증된 subject를
헤더·경로·쿼리에 각각 알맞게 옮겨야 했다.

## 본문은 고치지 않고 신원을 따로 보낸다

[`IdentityForwarding`](../src/main/java/com/sportsbook/gateway/routing/IdentityForwarding.java)은
JWT subject와 roles를 `X-User-Id`, `X-User-Roles`로 새로 넣는다. 익명 공개
경로에는 사용자 헤더를 만들지 않는다.

베팅 접수 본문에도 사용자 ID가 있지만 게이트웨이는 JSON을 해석하거나 고치지
않는다. 매체 형식마다 파서를 유지해야 하고 서명된 본문을 깨뜨릴 수도 있기
때문이다. 대신 betting-service가 전달된 헤더와 본문의 사용자가 같은지 업무
경계에서 다시 확인한다.

- 게이트웨이: 외부 신뢰 헤더 제거와 검증된 신원 전달
- betting-service: 업무 본문과 전달된 신원의 일치 검사

두 검사는 서로 다른 경계를 지킨다. 한쪽을 생략하면 사용자 위조 경로가 남는다.

## 목록 쿼리는 추가하지 않고 교체한다

[`GatewayRoutes`](../src/main/java/com/sportsbook/gateway/routing/GatewayRoutes.java)는
`/api/v1/bets`를 `/internal/v1/bets`로 바꾸고, 목록 조회의 `userId`를 JWT
subject로 강제한다. Spring Cloud Gateway Server MVC의 프록시 처리는 대상
쿼리를 `ServerRequest.params()`에서도 읽으므로 URI만 바꾸지 않고 builder의
params를 직접 고친다.

```java
if (HttpMethod.GET.equals(request.method()) && "/internal/v1/bets".equals(rewrittenPath)) {
  identity
      .currentSubject()
      .ifPresent(subject -> builder.params(params -> params.set("userId", subject)));
}
```

`add`를 사용하면 공격자가 보낸 값과 검증된 값이 함께 전달된다. 하위
프레임워크가 어느 값을 선택하느냐에 사용자 경계를 맡기지 않도록 `set`으로
교체했다. 지갑 잔액도 외부 경로에 사용자 ID를 받지 않고
`/internal/v1/wallet/accounts/{subject}/balance`로 다시 만든다.

경기·배당 route는 경로 조건만 있고 메서드 조건은 없다. 보안 설정이 익명 `GET`만
허용하므로 토큰 없는 변경 요청은 막히지만, 인증한 POST·PUT·DELETE는 odds-feed로
전달된다. 현재 하위 서비스가 405로 거절하는 동작에 기대고 있으며
`GatewayRoutingTest`도 공개 `GET`만 확인한다.

## 추적 정보는 권한과 분리한다

[`TraceForwarding`](../src/main/java/com/sportsbook/gateway/routing/TraceForwarding.java)은
들어온 `traceparent`가 있으면 유지하고, 없으면 현재 Micrometer span으로 값을
만든다. 직접 조립하는 값은 실제 span의 sampling 상태를 읽지 않고 flags를 항상
`01`로 기록한다. sampling하지 않은 span도 하위 서비스에는 sampling 대상으로
전달될 수 있으며 테스트는 이 flags까지 확인하지 않는다. tracing이 꺼졌거나 활성
span이 없어도 라우팅은 계속된다. 외부에서 온 trace ID는 관측 문맥이지 신뢰할 수
있는 사용자 식별자가 아니다.

## 프록시 뒤에서 받은 요청까지 확인했다

[`GatewayRoutingTest`](../src/test/java/com/sportsbook/gateway/routing/GatewayRoutingTest.java)는
임의 포트에서 띄운 게이트웨이와 WireMock 하위 서버를 실제 HTTP로 연결한다.

- 위조한 `X-User-Id`, `X-User-Roles`가 JWT 값으로 교체됨
- 베팅 POST 본문은 byte 단위로 유지됨
- 목록 쿼리의 `userId`는 subject로 교체되고 cursor는 보존됨
- 지갑 경로에 subject가 들어감
- 공개 경기 조회는 토큰 없이 전달됨
- 하위 RFC 7807 상태와 본문이 그대로 돌아옴
- `traceparent`가 전달됨

WireMock의 Jetty와 JDK HttpClient가 평문 HTTP/2를 협상하면 `RST_STREAM`이 날 수
있다. 테스트 서버의 HTTP/2 plain/TLS를 꺼 운영 하위 서비스와 같은 HTTP/1.1
조건으로 맞추면 프록시 로직 실패와 시험용 서버의 프로토콜 문제를 분리할 수 있다.
