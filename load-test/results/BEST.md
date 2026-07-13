# Gateway 검증·측정 상태

## 현 릴리스 판정

포트폴리오 hardening 이후 현재 소스로 `GatewayLoadTest`와 전체 스택 k6 시나리오를
다시 측정하지 않았습니다. 따라서 이 릴리스에는 WebSocket 동시 연결 수, 전달 지연,
REST 처리량, p99 또는 오류율 성능 주장이 없습니다.

현재 테스트는 인증 경계, 라우팅 계약, rate-limit의 Redis fail-open, Kafka 이벤트의
사용자별 STOMP 전달 같은 기능을 검증합니다. 이 기능 증거를 처리량 인증으로
해석하지 않습니다.

## Pre-hardening 역사 자료

[`2026-05-30/summary.md`](2026-05-30/summary.md)는 hardening 이전 소스를 gateway,
Embedded Kafka, WireMock 한 JVM에서 측정한 역사적 비교 자료입니다. 현재 릴리스의
대표 결과나 여러 호스트 운영 환경의 용량 증거가 아닙니다.

성능 수치를 다시 공개하려면 현재 gateway/shared SHA를 고정하고 broker ack, 분리된
하위 서비스, 연결 수, 장비·네트워크 조건을 함께 기록해 재측정해야 합니다.
