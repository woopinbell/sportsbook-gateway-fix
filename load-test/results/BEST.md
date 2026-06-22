# Gateway 부하 테스트 결과

2026년 5월 30일 `GatewayLoadTest`로 측정한 개발 환경 기준입니다. gateway와
Embedded Kafka, WireMock을 한 JVM에서 실행했습니다.

| 시나리오 | 항목 | 측정값 |
|---|---|---:|
| WebSocket fan-out | 최대 전달 지연 | 202ms |
| WebSocket fan-out | 전달 성공 | 500 / 500 |
| REST 라우팅 | 처리량 | 2,868 req/s |
| REST 라우팅 | p99 | 31ms |
| REST 라우팅 | 오류율 | 0.19% |

이 결과는 메시지 전달 방식과 라우팅 경로를 개발 환경에서 확인한 값입니다. 1만 동시
WebSocket 연결이나 서비스가 여러 호스트에 분산된 환경의 성능을 뜻하지 않습니다.
측정 조건과 범위는 [상세 결과](2026-05-30/summary.md)에서 확인할 수 있습니다.
