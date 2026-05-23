# Testing Evidence

이 문서는 AI Usage Billing Gateway의 포트폴리오 claim을 어떤 테스트가 지지하는지와 아직 claim하지 않는
범위를 분리합니다. 새 수치는 추가하지 않습니다.

## 검증된 범위

| 범위 | 대표 테스트 | 검증하는 주장 |
| --- | --- | --- |
| tenant isolation / RBAC | `ApiKeyUsageQuotaIT`, `BillingPaymentLedgerAuditIT` | organization-scoped API 접근 제어와 admin-only billing 변경 |
| API key 보안 | `ApiKeyUsageQuotaIT` | raw key는 생성 시 1회 반환, DB에는 prefix/hash 저장 |
| usage idempotency | `ApiKeyUsageQuotaIT` | 같은 idempotency key + 같은 payload duplicate, 다른 payload conflict |
| gateway retry idempotency | `ApiKeyUsageQuotaIT` | `gateway:` scope로 explicit usage key와 분리 |
| gateway timestamp consistency | `GatewayServiceTest`, `ApiKeyUsageQuotaIT` | gateway request 시각을 한 번 캡처해 usage record에 같은 `occurredAt`을 전달하고, quota counter reservation은 통합 테스트에서 검증 |
| low-cardinality outcome metrics | `MetricsServiceTest`, gateway/quota/usage/webhook/ledger unit tests | gateway request/rate-limit, idempotency conflict, webhook conflict, ledger group counter 등록과 호출 경로 |
| quota / rate limit | `ApiKeyUsageQuotaIT`, `QuotaServiceTest` | gateway/explicit usage insert + 같은 transaction의 `quota_counters` reservation, `occurredAt` 기준 UTC 월별 counter 분리, Redis 장애 fail-closed 503 |
| invoice idempotency | `BillingPaymentLedgerAuditIT`, `MonthlyInvoiceSchedulerIT`, `MonthlyInvoiceSchedulerTest` | organization + period unique constraint, scheduler path, active subscription organization deduplication |
| payment webhook duplicate/conflict | `BillingPaymentLedgerAuditIT` | provider event id + payload hash 기준 duplicate/conflict, 같은 invoice의 두 번째 capture 차단 |
| append-only ledger / audit | `BillingPaymentLedgerAuditIT`, ledger/audit unit tests | ledger/audit update/delete 거부, balanced entry, partial refund별 별도 reversal group, refund reversal의 original ledger group 추적, audit metadata sanitizer의 nested Map/List redaction |
| k6 script syntax/options | `node --check k6/mixed-usage-test.js`, `k6 inspect k6/mixed-usage-test.js`, `k6 inspect -e K6_REQUIRE_OPTIONAL_PATHS=true k6/mixed-usage-test.js` | default / optional threshold 로딩 |
| full mixed evidence capture syntax | `bash -n scripts/run-full-mixed-evidence.sh` | 반복 full mixed artifact 수집 스크립트의 shell syntax |
| full mixed summary validator | `node scripts/validate-k6-full-mixed-summary.mjs docs/evidence/k6/mixed-usage-full-20260522081328-summary.json`, superseded diagnostic artifact reject check | k6 summary-export의 `value/count` metric shape에서 checks 100%, HTTP failure 0, invoice/webhook branch 실행, optional skip 0 검증 |
| full mixed capture rollup | `node scripts/test-k6-evidence-tools.mjs` | `full-mixed-<timestamp>/run-N/summary.json` 반복 capture를 `capture-summary.json` readiness metadata로 묶고, 실패/누락 summary를 거부 |
| full mixed local repeat3 | `docs/evidence/k6/full-mixed-20260523T015029Z/` | 5 VU, 30s, repeat3에서 checks 150/150/run, HTTP failure 0/150/run, optional skip 0 확인 |
| k6 local smoke path counters | `k6 run k6/mixed-usage-test.js` | gateway/usage path가 실제 smoke 실행에서 모두 1회 이상 실행되는지 counter로 확인 |

## 아직 검증하지 않는 범위

| 범위 | 현재 상태 |
| --- | --- |
| gateway + usage throughput / latency | local smoke 결과는 있으나 benchmark claim은 하지 않음 |
| invoice/webhook branch throughput / latency | full mixed local repeat3에서 branch 실행과 checks 150/150/run 확인, production benchmark claim은 하지 않음 |
| full mixed production benchmark 결과 | 추가 측정 예정 |
| production scheduler operations | distributed lock, partitioned batch, persistent retry table 미구현 |
| quota reconciliation / operations | `quota_counters` 월별 reservation과 UTC period 경계는 `ApiKeyUsageQuotaIT`에서 검증했지만, 운영용 reconciliation job, dashboard, alert rule은 아직 claim하지 않음 |
| refund accounting compliance | captured payment amount 초과, partial refund reversal group, 병렬 refund 초과, reversal ledger와 original group 추적은 검증, 실제 회계 compliance는 주장하지 않음 |
| alert/dashboard/tracing/SLO | low-cardinality metric counter 등록 수준 |

## 실행 명령

```bash
node --check k6/mixed-usage-test.js
bash -n scripts/run-full-mixed-evidence.sh
node --check scripts/validate-k6-full-mixed-summary.mjs
node --check scripts/summarize-full-mixed-evidence.mjs
node --check scripts/test-k6-evidence-tools.mjs
node scripts/validate-k6-full-mixed-summary.mjs docs/evidence/k6/mixed-usage-full-20260522081328-summary.json
node scripts/test-k6-evidence-tools.mjs
k6 inspect k6/mixed-usage-test.js
k6 inspect -e K6_REQUIRE_OPTIONAL_PATHS=true k6/mixed-usage-test.js
./gradlew test --no-daemon
./gradlew build --no-daemon
```

## 해석 원칙

- throughput/latency/error rate는 local repeat3 artifact와 production benchmark claim을 분리합니다.
- ledger는 append-only invariant를 보여주는 포트폴리오 모델이며 accounting compliance claim이 아닙니다.
- quota는 gateway mock completion과 explicit usage ingestion의 usage insert + `quota_counters` reservation 범위로 설명합니다.
