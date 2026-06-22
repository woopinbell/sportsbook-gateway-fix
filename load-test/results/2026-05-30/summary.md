# 2026년 5월 30일 Gateway 측정 결과

macOS 개발 환경에서 JDK 21과 Spring Boot 3.2.11을 사용했습니다. rate limit은
비활성화했고, STOMP 시험은 Embedded Kafka를, 라우팅 시험은 WireMock을 사용했습니다.

```sh
./mvnw test -Dsurefire.excludedGroups= -Dtest=GatewayLoadTest
```

## WebSocket fan-out

| 항목 | 측정값 |
|---|---:|
| 동시 STOMP 구독자 | 500 |
| 메시지 수신 | 500 / 500 |
| 최대 전달 지연 | 약 210ms |

각 구독자가 `/topic/odds/{eventId}`를 구독한 뒤 Kafka에 배당 변경 이벤트 하나를
발행했습니다. 모든 구독자가 메시지를 받았으며 실행별 최대 지연은 202~223ms였습니다.

## REST 라우팅

| 항목 | 측정값 |
|---|---:|
| 요청 수 | 32,000 |
| 처리량 | 2,368~2,868 req/s |
| p99 | 약 30~43ms |
| 오류율 | 0.2~0.6% |

32개 스레드가 각각 1,000개 요청을 gateway를 거쳐 WireMock으로 보냈습니다.
클라이언트, gateway, WireMock이 한 JVM에서 두 번의 HTTP 전달을 처리한 값이므로
운영 환경의 상한으로 해석해서는 안 됩니다. 이 시험은 지속적인 동시 요청에서도
라우팅 경로가 동작하는지 확인하는 기준으로 사용합니다.
