# Gateway 부하 테스트

REST 라우팅과 STOMP 메시지 전달을 두 가지 방식으로 확인합니다.

## 개발 환경 기준

`GatewayLoadTest`는 게이트웨이 한 JVM과 내장 Kafka, WireMock을 사용합니다.
운영 규모를 재현하는 시험은 아니지만 메시지 손실과 전달 지연, 라우팅 처리량을 빠르게
확인할 수 있습니다. 안정화 작업 이후 현재 소스로는 다시 측정하지
않았으므로 날짜별 저장 결과는 현재 코드의 성능 수치가 아닙니다.

```sh
./mvnw test -Dsurefire.excludedGroups= -Dtest=GatewayLoadTest
```

## 전체 스택 시나리오

전체 서비스가 실행 중일 때 k6로 WebSocket 연결과 HTTP 부하를 만듭니다.

```sh
k6 run --vus 10000 --duration 2m \
  -e WS_URL=ws://localhost:8080/ws/v1/odds -e EVENT_ID=<eventId> \
  scenarios/ws_fanout.js

k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=<jwt> \
  scenarios/gateway_routing.js
```

`ws_fanout.js`는 STOMP `CONNECT`와 이벤트별 `SUBSCRIBE`를 수행하고 첫 메시지까지의
지연을 기록합니다. `gateway_routing.js`는 공개 이벤트 조회와 인증이 필요한 베팅
경로에 부하를 주고 상태 코드, p99, 오류율을 확인합니다.

현재 코드의 측정 상태와 안정화 전 자료의 범위는
[`results/BEST.md`](results/BEST.md)에 정리하며, 실행별 원본은
`results/<날짜>/`에 보관합니다.
