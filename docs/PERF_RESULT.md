# Performance Result

## 공개 부하 테스트 결과

Claim boundary: 현재 공개 가능한 production throughput / latency / error-rate 측정 완료 수치는 없습니다.
아래 full mixed repeat3는 local 환경에서 branch mix와 HTTP 결과를 확인한 측정 evidence이며, 운영 성능 주장으로
사용하지 않습니다.

## Full Mixed Local Repeat3 Evidence

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<accessToken> \
ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=<webhookSecret> \
WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> \
INVOICE_PERIOD=2026-05 \
K6_RUNS=3 \
K6_VUS=5 \
K6_DURATION=30s \
scripts/run-full-mixed-evidence.sh
```

| Run | HTTP requests | RPS | HTTP p95 | Checks | HTTP failed | gateway | usage | invoice | webhook | optional skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| run-1 | 150 | 4.8567 | 44.1454ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |
| run-2 | 150 | 4.9255 | 19.8888ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |
| run-3 | 150 | 4.9329 | 18.5976ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |

- Artifact: `docs/evidence/k6/full-mixed-20260523T015029Z/`
- Summary: `docs/evidence/FULL_MIXED_REPEAT3_2026-05-23.md`
- Prometheus scrape는 best-effort입니다. local `/actuator/prometheus`는 인증 정책으로 `401`을 반환해
  unavailable note를 artifact에 남겼습니다.
- webhook branch는 같은 invoice + 같은 `providerEventId`를 재사용하므로 fresh payment throughput이 아니라
  duplicate delivery / idempotency path로 해석합니다.

## 시나리오 검증

- `k6/mixed-usage-test.js`는 gateway 호출과 직접 usage ingestion 경로를 기본으로 확인합니다.
- 2026-05-22 local smoke에서 gateway path와 usage path가 모두 1회 이상 실행되고 request failure가 없는지
  counter threshold로 확인했습니다.
- `JWT_TOKEN`, `ORG_ID`, `WEBHOOK_INVOICE_ID`, `WEBHOOK_SECRET`을 제공하면 invoice generation과
  payment webhook branch도 선택 실행할 수 있습니다.
- `K6_REQUIRE_OPTIONAL_PATHS=true`를 설정하면 invoice/webhook branch가 1회 이상 실행되고 optional skip이
  0건인지 threshold로 강제합니다.
- `K6_REQUIRE_OPTIONAL_PATHS=true`는 benchmark 결과가 아니라 full mixed smoke readiness guard입니다. 이
  모드에서는 모든 check 통과, HTTP failure 0, invoice/webhook branch 1회 이상 실행, optional skip 0건만
  검증합니다.
- 2026-05-22 local full mixed smoke에서 `K6_REQUIRE_OPTIONAL_PATHS=true`로 gateway, usage, invoice,
  webhook branch가 모두 실행되고 optional skip이 0건인지 확인했습니다. 5 VU refresh에서도 모든 branch가
  실행되고 checks 150/150, HTTP failure 0/150을 확인했습니다. 단일 local smoke refresh이므로 정식
  throughput/latency/error-rate benchmark 결과로 공개하지 않습니다.
- webhook branch는 같은 invoice에 대해 같은 `providerEventId`를 재사용해 duplicate delivery 입력을 확인합니다.
  `WEBHOOK_AMOUNT_MINOR`는 대상 invoice의 `totalAmountMinor`와 일치해야 하며, 이 branch는 fresh payment
  throughput이 아니라 duplicate/idempotency smoke로만 해석합니다.
- local full mixed repeat3 측정은 2026-05-23 artifact로 기록했습니다. production benchmark는 아직 주장하지 않습니다.
- 통합 테스트로 security, idempotency, invoice, webhook, ledger, audit 동작의 기능 정합성을 검증합니다.

## Local Smoke Evidence

```bash
BASE_URL=http://localhost:8080 API_KEY=<rawApiKey> K6_VUS=1 K6_DURATION=30s \
k6 run k6/mixed-usage-test.js \
  --summary-export docs/evidence/k6/mixed-usage-smoke-2026-05-22-summary.json
```

| 항목 | 결과 |
| --- | ---: |
| checks | 28/28 passed |
| gateway path count | 24 |
| usage path count | 4 |
| skipped optional path count | 2 |
| http request failed | 0/28 |

이 smoke는 gateway / usage ingestion branch 실행 여부를 확인한 결과입니다. public throughput, latency,
error-rate benchmark로 사용하지 않습니다. 요약은
[docs/evidence/MIXED_USAGE_SMOKE_2026-05-22.md](evidence/MIXED_USAGE_SMOKE_2026-05-22.md)에 분리했습니다.

## Full Mixed Local Smoke Evidence

```bash
BASE_URL=http://localhost:8080 API_KEY=<rawApiKey> JWT_TOKEN=<accessToken> ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> WEBHOOK_SECRET=<webhookSecret> WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> K6_REQUIRE_OPTIONAL_PATHS=true \
K6_VUS=1 K6_DURATION=30s \
k6 run k6/mixed-usage-test.js \
  --summary-export docs/evidence/k6/mixed-usage-full-20260522154446-summary.json
```

| 항목 | 결과 |
| --- | ---: |
| checks | 30/30 passed |
| gateway path count | 24 |
| usage path count | 4 |
| invoice path count | 1 |
| webhook path count | 1 |
| skipped optional path count | 0 |
| http request failed | 0/30 |

이 실행은 invoice / webhook branch까지 포함한 local smoke입니다. public throughput, latency, error-rate
benchmark로 사용하지 않습니다. 요약은
[docs/evidence/MIXED_USAGE_FULL_2026-05-22.md](evidence/MIXED_USAGE_FULL_2026-05-22.md)에 분리했습니다.
artifact 해석 기준은 [docs/evidence/K6_EVIDENCE_MANIFEST.md](evidence/K6_EVIDENCE_MANIFEST.md)에 정리했습니다.

### 5 VU refresh

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

이 실행은 모든 optional path가 실제로 호출되고 threshold가 통과함을 확인한 local smoke refresh입니다.
반복 실행, 고정된 실행 환경, 데이터셋 규모가 아직 없으므로 반복 가능한 benchmark 수치로 승격하지 않습니다.
위 p95는 local smoke refresh artifact에 남은 관찰값이며, public latency benchmark로 사용하지 않습니다.

raw artifact:

- `docs/evidence/k6/mixed-usage-full-20260522081328-summary.json`
- `docs/evidence/k6/mixed-usage-full-20260522081328-console.txt`

## 반복 benchmark protocol

local repeat3 artifact는 생성됐지만, claim boundary: production benchmark로 승격하려면 아래 조건을 더 고정해야 합니다.

- k6 실행 전 signup, organization creation, API key creation 흐름으로 실제 local `rawApiKey`를 생성합니다.
- 대상 script는 `k6/mixed-usage-test.js`이며, branch mix는 gateway 70%, direct usage ingestion 20%,
  invoice generation 5%, payment webhook 5%입니다.
- 기본 smoke는 local 확인용 `K6_VUS=1`, `K6_DURATION=30s`로 둡니다.
- 더 큰 부하를 줄 때는 `GATEWAY_RATE_LIMIT_PER_MINUTE=1000`처럼 gateway rate limit을 테스트 조건에
  맞춰 올린 뒤 `BASE_URL=http://localhost:8080 API_KEY=<rawApiKey> K6_VUS=5 K6_DURATION=30s k6 run
  k6/mixed-usage-test.js`를 실행합니다.
- invoice/webhook branch까지 포함한 repeat3는 실행했지만, claim boundary: production benchmark로 공개하려면 `JWT_TOKEN`,
  `ORG_ID`, `WEBHOOK_INVOICE_ID`, `WEBHOOK_SECRET`, `WEBHOOK_AMOUNT_MINOR`, `K6_REQUIRE_OPTIONAL_PATHS=true`와
  함께 실행 환경과 데이터셋 조건을 더 고정해야 합니다.
- required guard는 `checks == 100%`, `http_req_failed == 0`, `invoice_path_count > 0`,
  `webhook_path_count > 0`, `skipped_optional_path_count == 0`입니다.
- 기록할 환경은 hardware, OS, JVM, PostgreSQL, Redis, Spring profile/config, dataset size, rate-limit 설정,
  실행 command입니다.
- 기록할 결과는 throughput, p50/p95/p99 latency, error rate, duplicate/conflict count, quota/rate-limit count입니다.
- 같은 환경과 seed 절차에서 N회 이상 반복 실행한 뒤 결과를 별도 evidence artifact로 보관합니다.
- full mixed benchmark 결과를 올릴 때는 gateway, usage ingestion, invoice, webhook 경로의 비율과 데이터셋을 함께 기록합니다.
- 반복 실행 artifact는 `scripts/run-full-mixed-evidence.sh`로 수집합니다. 이 스크립트는 k6 summary/console,
  Prometheus before/after sample, sanitized metadata를 `docs/evidence/k6/full-mixed-<timestamp>/`에 저장하고
  `scripts/validate-k6-full-mixed-summary.mjs`로 checks 100%, HTTP failure 0, invoice/webhook branch 실행,
  optional skip 0 조건을 확인합니다.
- 같은 디렉터리의 `capture-summary.json`은 `scripts/summarize-full-mixed-evidence.mjs`가 만드는
  readiness metadata입니다. run별 summary path와 guard 통과 여부만 묶으며 throughput/latency/error-rate
  benchmark aggregate를 만들지 않습니다.
- capture script가 만든 artifact는 review 후 local repeat3 측정 evidence로 기록합니다. Claim boundary: production
  throughput/latency/error-rate benchmark로는 승격하지 않습니다.

합성 수치나 추정 benchmark 수치는 추가하지 않습니다.
