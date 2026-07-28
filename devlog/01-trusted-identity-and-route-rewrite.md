# 신뢰할 수 있는 사용자 전달과 경로 변환

게이트웨이 뒤의 서비스는 매 요청에서 JWT를 다시 검증하지 않고 `X-User-Id`와
`X-User-Roles`를 신뢰합니다. 따라서 외부 클라이언트가 같은 헤더를 보낼 수 있는
상태에서 단순히 JWT 값을 추가하면 복수 헤더나 잘못된 우선순위로 신원을 위조할 수
있습니다.

## 외부 신뢰 헤더를 먼저 숨긴다

`TrustedHeaderFilter`는 가장 높은 filter 순서로 실행되며 다음 세 API를 모두
가립니다.

- `getHeader()`
- `getHeaders()`
- `getHeaderNames()`

헤더 이름은 대소문자를 구분하지 않습니다. 한 API만 override하면 프레임워크나 다른
필터가 나머지 API로 원래 값을 읽을 수 있습니다.

```java
public String getHeader(String name) {
  return isStripped(name) ? null : super.getHeader(name);
}
```

JWT 검증 뒤 `IdentityForwarding`이 subject와 roles를 새로 넣습니다. 익명 공개
경로에는 아무 사용자 헤더도 추가하지 않습니다. 내부 서비스가 이 값을 신뢰하려면
게이트웨이를 거치지 않는 외부 접근도 네트워크에서 차단해야 합니다.

## 본문을 바꾸지 않는 이유

베팅 접수 본문에는 사용자 ID가 들어갈 수 있지만 게이트웨이는 JSON을 해석하거나
고치지 않습니다. content type마다 parser를 유지해야 하고 서명된 본문을 깨뜨릴 수
있기 때문입니다. 대신 검증한 `X-User-Id`를 전달하고 betting-service가 본문의
사용자와 일치하는지 마지막으로 확인합니다.

이 선택은 책임을 두 곳에 나눕니다.

- 게이트웨이: 외부 헤더 제거와 검증된 신원 전달
- betting-service: 업무 본문과 전달된 신원의 일치 검사

둘 중 한쪽만 구현하면 사용자 경계가 완성되지 않습니다.

## 쿼리 값을 덮어쓰는 방법

베팅 목록은 클라이언트가 보낸 `userId`를 사용할 수 없습니다. 공개
`/api/v1/bets`를 `/internal/v1/bets`로 바꾸면서 JWT subject를 query parameter로
강제합니다.

Spring Cloud Gateway Server MVC의 proxy handler는 target query를
`ServerRequest.params()`에서도 읽습니다. URI만 새로 만들면 원래 parameter가 남을
수 있어 builder의 params를 직접 바꿉니다.

```java
identity.currentSubject()
    .ifPresent(subject -> builder.params(params -> params.set("userId", subject)));
```

`set` 대신 `add`를 쓰면 공격자가 보낸 값과 검증된 값이 동시에 전달됩니다. 하위
프레임워크가 첫 값이나 마지막 값 중 무엇을 선택하는지에 따라 취약점이 다시 생깁니다.

지갑 잔액 경로도 클라이언트의 user ID를 받지 않고
`/internal/v1/wallet/accounts/{subject}/balance`로 만듭니다.

## trace 전달

들어온 `traceparent`가 있으면 그대로 전달하고, 없으면 현재 Micrometer span으로
새 값을 만듭니다. 외부 trace를 계속 이어가는 선택이므로 trace ID를 권한이나
업무 식별자로 사용하면 안 됩니다. trace가 없거나 tracing이 비활성화되어도 요청
라우팅은 계속됩니다.

## 실제 HTTP로 검증한다

`GatewayRoutingTest`는 random port의 gateway와 WireMock 하위 서버를 실제 HTTP로
연결합니다. 다음을 함께 확인합니다.

- 위조한 `X-User-Id`, `X-User-Roles`가 JWT 값으로 교체됨
- 베팅 POST 본문은 byte 단위로 유지됨
- 목록 query의 `userId`는 subject로 교체되고 cursor는 보존됨
- 지갑 경로에 subject가 들어감
- 공개 경기 조회는 토큰 없이 전달됨
- 하위 RFC 7807 본문과 상태가 유지됨
- `traceparent`가 전달됨

WireMock의 Jetty와 JDK HttpClient가 평문 HTTP/2를 협상하면 테스트에서
`RST_STREAM`이 날 수 있습니다. 이 테스트는 WireMock의 HTTP/2 plain/TLS를 꺼서
운영 하위 서비스와 같은 HTTP/1.1 조건으로 맞춥니다. 프록시 로직 실패와 테스트 서버
프로토콜 문제를 구분하기 위한 설정입니다.

```sh
./mvnw -Dtest=GatewayRoutingTest,TrustedHeaderFilterTest test
```
