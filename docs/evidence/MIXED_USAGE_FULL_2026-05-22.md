# Mixed Usage Full Local Smoke - 2026-05-22

이 문서는 `k6/mixed-usage-test.js`를 `K6_REQUIRE_OPTIONAL_PATHS=true`로 실행해 gateway, direct usage ingestion,
invoice generation, payment webhook branch가 모두 실제 HTTP 요청으로 실행되는지 확인한 local smoke 증거입니다.
정식 처리량/latency/error-rate benchmark 결과로 공개하지 않습니다.

## Command Shape

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<jwt> \
ORG_ID=<organizationId> \
INVOICE_PERIOD=2026-05 \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=local-dev-webhook-secret \
WEBHOOK_AMOUNT_MINOR=2900 \
K6_REQUIRE_OPTIONAL_PATHS=true \
K6_VUS=1 \
K6_DURATION=30s \
k6 run k6/mixed-usage-test.js \
  --summary-export docs/evidence/k6/mixed-usage-full-20260522154446-summary.json
```

## Local Setup

- PostgreSQL: `docker compose up -d postgres`
- Redis: Docker Redis mapped to `127.0.0.1:6380`
- App: `GATEWAY_RATE_LIMIT_PER_MINUTE=1000 SPRING_DATA_REDIS_PORT=6380 ./gradlew bootRun --no-daemon`
- Test data: unique signup user, organization, PRO subscription, API key, invoice generated through local HTTP API

## k6 Summary

| 항목 | 결과 |
| --- | ---: |
| checks | 30/30 passed |
| gateway path count | 24 |
| usage path count | 4 |
| invoice path count | 1 |
| webhook path count | 1 |
| skipped optional path count | 0 |
| http request failed | 0/30 |

## Raw Evidence

- `docs/evidence/k6/mixed-usage-full-20260522154446-summary.json`
- `docs/evidence/k6/mixed-usage-full-20260522154446-console.txt`

## 5 VU Full Mixed Local Smoke Refresh

같은 날 `K6_VUS=5`, `K6_DURATION=30s`, `K6_REQUIRE_OPTIONAL_PATHS=true`로 다시 실행했습니다.
이 실행은 gateway, direct usage ingestion, invoice generation, payment webhook branch가 모두 호출되고
모든 check가 통과하는지 확인한 local smoke refresh입니다. 정식 benchmark가 아니라 branch coverage와
idempotent webhook retry 입력을 확인하는 증거로만 해석합니다.

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<jwt> \
ORG_ID=<organizationId> \
INVOICE_PERIOD=2026-05 \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=local-dev-webhook-secret \
WEBHOOK_AMOUNT_MINOR=2900 \
K6_REQUIRE_OPTIONAL_PATHS=true \
K6_VUS=5 \
K6_DURATION=30s \
k6 run k6/mixed-usage-test.js \
  --summary-export docs/evidence/k6/mixed-usage-full-20260522081328-summary.json
```

| 항목 | 결과 |
| --- | ---: |
| checks | 150/150 passed |
| gateway path count | 107 |
| usage path count | 31 |
| invoice path count | 6 |
| webhook path count | 6 |
| skipped optional path count | 0 |
| HTTP requests | 150 |
| HTTP request failed | 0/150 |
| HTTP p95 관찰값 | 30.38ms |

Raw evidence:

- `docs/evidence/k6/mixed-usage-full-20260522081328-summary.json`
- `docs/evidence/k6/mixed-usage-full-20260522081328-console.txt`

## Claim Boundary

- `K6_REQUIRE_OPTIONAL_PATHS=true`에서 optional branch skip 없이 full mixed branch가 실행됨을 확인했습니다.
- 이 실행은 local smoke이며, 반복 가능한 throughput/latency/error-rate benchmark 수치로 사용하지 않습니다.
- 위 p95는 local smoke refresh artifact에 남은 관찰값이며, public latency benchmark로 사용하지 않습니다.
- 더 큰 부하, 반복 실행, hardware/JVM/DB/Redis 조건 고정, 데이터셋 규모 기록은 추가 측정 예정입니다.
