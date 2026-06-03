# gateway

`gateway`는 REST와 WebSocket 요청을 받는 스포츠북 시스템의 외부 진입점입니다. 공통 보안과 전달 작업을 처리하고 도메인 로직은 내부 서비스에 맡깁니다.

## 현재 구현 범위

- Spring Cloud Gateway Server MVC 기반 프로젝트 구성
- RS256 JWT 검증과 외부 신뢰 헤더 제거

## 빌드

```sh
(cd ../sportsbook-shared-protocol && ./mvnw install)
./mvnw verify
```
