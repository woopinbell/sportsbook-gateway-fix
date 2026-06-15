# gateway

`gateway`는 REST와 WebSocket 요청을 받는 스포츠북 시스템의 외부 진입점입니다. 공통 보안과 전달 작업을 처리하고 도메인 로직은 내부 서비스에 맡깁니다.

## 현재 구현 범위

- Spring Cloud Gateway Server MVC 기반 프로젝트 구성
- RS256 JWT 검증과 외부 신뢰 헤더 제거
- Redis를 공유하는 사용자별·IP별 토큰 버킷
- 내부 서비스 경로 변환, 사용자 정보와 traceparent 전달
- Kafka의 배당과 정산 이벤트를 STOMP 구독자에게 전달
- 검증된 사용자만 베팅 경로에 전달되는지 회귀 검증

## 빌드

```sh
(cd ../sportsbook-shared-protocol && ./mvnw install)
./mvnw verify
```
