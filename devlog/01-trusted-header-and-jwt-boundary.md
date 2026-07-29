# 외부 신뢰 헤더를 지우고 JWT를 검증하기

게이트웨이 뒤의 서비스는 `X-User-Id`와 `X-User-Roles`를 내부 신원으로 사용한다.
외부 요청에 같은 이름의 헤더가 남아 있는 상태에서 JWT 값을 덧붙이면 복수 헤더의
첫 값과 마지막 값 중 어느 쪽을 읽느냐에 따라 신원을 위조할 수 있다. 라우팅을
만들기 전에 외부 값이 어느 요청 API에서도 보이지 않게 하는 작업부터 필요했다.

## 헤더를 추가하기 전에 가린다

[`TrustedHeaderFilter`](../src/main/java/com/sportsbook/gateway/security/TrustedHeaderFilter.java)는
가장 높은 필터 순서에서 요청을 감싸고 다음 세 API를 모두 가린다.

- `getHeader()`
- `getHeaders()`
- `getHeaderNames()`

헤더 이름은 대소문자를 구분하지 않는다. 한 API만 재정의하면 프레임워크나 뒤의
필터가 다른 API로 원래 값을 읽을 수 있다.

```java
public String getHeader(String name) {
  return isStripped(name) ? null : super.getHeader(name);
}
```

[`GatewayHeaders`](../src/main/java/com/sportsbook/gateway/security/GatewayHeaders.java)는
신뢰 헤더 이름을 한곳에 둔다. 제거 필터와 뒤의 신원 전달 코드가 각자 문자열을
가지면 새 헤더를 추가할 때 한쪽만 고쳐 외부 값이 다시 새어 들어갈 수 있다.

## 공개 경로와 인증 경로를 나눴다

[`SecurityConfig`](../src/main/java/com/sportsbook/gateway/security/SecurityConfig.java)는
상태 확인, 공개 경기·배당 조회와 WebSocket 연결을 `permitAll`로 두고 나머지
요청에는 검증된 JWT를 요구한다. `permitAll`은 토큰을 무시한다는 뜻이 아니다.
공개 경로라도 bearer token이 들어오면 리소스 서버 필터가 검증하고, 유효한
인증 정보는 뒤의 호출 제한과 WebSocket 연결에서 사용할 수 있다.

JWT의 `roles` 배열은 `ROLE_*` 권한으로 바꾼다. 토큰이 없거나 만료됐거나 다른
키로 서명됐다면 보호 경로는 401로 끝난다. 공개 조회가 열려 있다는 사실과 위조한
토큰을 받아들인다는 것은 다른 조건이다.

현재 리소스 서버는 공개키로 서명과 존재하는 시간 클레임만 확인하고 issuer,
audience와 비어 있지 않은 `sub`를 요구하지 않는다. `exp` 클레임의 존재도 필수가
아니어서 만료 시각이 없는 토큰이 통과할 수 있다. 같은 키로 서명한 다른 서비스용
토큰도 용도를 구분하지 못한다. `sub` 클레임이 없는 서명 토큰은 보호 경로의
`authenticated` 조건을 통과하지만 사용자 흐름은 서로 다르게 무너진다.

- 호출 제한은 사용자 대신 IP 버킷을 고른다.
- `IdentityForwarding`은 `X-User-Id`를 넣지 않고 목록의 `userId`도 강제하지 않는다.
- 지갑 잔액 경로는 `sub`를 넣은 내부 경로로 바뀌지 않는다.
- STOMP CONNECT도 같은 `JwtDecoder`를 사용해 사용자 ID가 없는 principal을 만들 수 있다.

하위 서비스가 결국 요청을 거절할 수는 있어도, 인증 경계가 유효한 사용자 신원을
보장한 것은 아니다. 이 공개키를 여러 용도에서 쓴다면 issuer·audience와 비어 있지
않은 `sub`, 필수 `exp` 클레임을 검사하는 검증기를 함께 추가해야 한다.

설정값이 없으면 `GATEWAY_JWT_PUBLIC_KEY`는 저장소의 `jwt/dev-public.pem`으로
대체된다. 개발 편의를 위한 기본값이라 운영 키가 빠져도 기동을 거부하지 않는다.
운영 배포에서는 공개키 경로를 반드시 덮어써야 한다.

[`JwtAuthenticationTest`](../src/test/java/com/sportsbook/gateway/security/JwtAuthenticationTest.java)는
정상·만료·다른 키 서명을 나눠 검사하고,
[`TrustedHeaderFilterTest`](../src/test/java/com/sportsbook/gateway/security/TrustedHeaderFilterTest.java)는
세 요청 API에서 대소문자가 다른 신뢰 헤더까지 사라지는지 확인한다.

## 애플리케이션 필터만으로 끝나지 않는다

뒤의 서비스가 이 헤더를 신뢰하려면 인터넷에서 해당 서비스로 직접 들어오는
경로가 없어야 한다. 게이트웨이 앞에서 `X-Forwarded-For`를 정리하고, 서비스
네트워크는 게이트웨이와 허용된 내부 호출자만 접근하게 해야 한다. 신뢰 헤더
제거는 네트워크 경계가 있다는 전제에서 내부 요청을 만들기 위한 첫 단계다.
