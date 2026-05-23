# AI Usage Billing Gateway Runbook

이 문서는 로컬 검증과 면접 설명을 위한 운영 절차 초안입니다. 실제 production 운영 체계,
alerting, dashboard, tracing, SLO를 구현했다는 주장은 하지 않습니다.

## 1. Invoice 중복 실행

### 증상

- 같은 organization과 billing period에 invoice generation 요청이 반복됩니다.
- 응답의 `duplicate` 값이 `true`로 반환될 수 있습니다.

### 확인

1. `invoices`에서 `organization_id + billing_period` row가 하나인지 확인합니다.
2. `invoice_items`와 `ledger_entries`가 같은 invoice에 중복 생성되지 않았는지 확인합니다.
3. audit log에서 같은 period의 generation 요청 횟수를 확인합니다.

### 판단

- 현재 invoice generation은 `organizationId + billingPeriod` unique constraint로 멱등성을 보장합니다.
- 수동 endpoint는 테스트와 운영자 재처리 용도입니다.
- `MonthlyInvoiceScheduler`는 `billing.invoice-scheduler.enabled=true`에서 이전 월 invoice를 생성합니다.
- scheduler 중복 실행도 같은 unique constraint를 통해 invoice row 1개로 수렴해야 합니다.
- 한 organization의 invoice 생성이 실패해도 같은 run의 나머지 organization 처리는 계속 시도하고,
  마지막에 실패 organization 목록을 포함한 summary exception을 던져 실패를 숨기지 않습니다.
- distributed scheduler lock, partitioned batch, retry summary는 아직 운영 claim으로 말하지 않습니다.

## 2. Payment Webhook Duplicate / Conflict

### Duplicate

같은 `providerEventId`와 같은 payload가 다시 도착하면 duplicate로 처리합니다.

확인할 것:

- `payment_webhook_events.provider_event_id`
- 저장된 `payload_hash`
- 응답의 `duplicate = true`

### Conflict

같은 `providerEventId`인데 payload hash가 다르면 `409 Conflict`입니다.

조치:

1. provider event id가 실제로 같은 이벤트인지 확인합니다.
2. payload 변조 또는 provider retry 정책 차이를 확인합니다.
3. conflict webhook은 자동으로 성공 처리하지 않습니다.

## 3. Refund Reversal Ledger

refund `payment.refunded` webhook은 기존 ledger row를 수정하지 않고
`PAYMENT_REFUNDED` ledger entry group을 새로 추가합니다.

```text
DEBIT  ACCOUNTS_RECEIVABLE
CREDIT CASH
```

확인할 것:

- `payments.status = REFUNDED` row가 provider event id 기준으로 한 번만 생성됐는지 확인합니다.
- refund 금액이 같은 invoice/currency의 captured payment amount를 넘지 않았는지 확인합니다.
- `ledger_entries.type = PAYMENT_REFUNDED` row가 debit/credit 균형을 이루는지 확인합니다.
- refund reversal row의 `original_transaction_group_id`가 captured payment의
  `PAYMENT_SUCCEEDED` transaction group을 가리키는지 확인합니다.
- 중복 refund webhook은 duplicate로 처리되어 reversal row를 다시 만들지 않아야 합니다.

주의:

- 이 프로젝트는 simplified balanced-entry model입니다.
- original ledger group 추적은 구현했지만, 실제 회계 compliance는 주장하지 않습니다.
- 실제 정산 compliance나 회계 기준 준수를 주장하지 않습니다.

## 4. Ledger Imbalance

### 증상

- ledger append 과정에서 debit 합계와 credit 합계가 다릅니다.
- `LedgerService`가 `IllegalStateException`으로 저장을 중단합니다.

### 조치

1. 해당 invoice/payment id와 transaction group을 확인합니다.
2. 같은 currency로만 entry group이 구성됐는지 확인합니다.
3. 기존 ledger row를 수정하지 않고, 필요한 경우 별도 reversal/adjustment group으로 보정합니다.

## 5. Quota / Rate Limit

FREE처럼 overage를 허용하지 않는 plan에서 gateway mock completion과 explicit usage ingestion은
usage event를 저장한 뒤 같은 transaction 안에서 `quota_counters`의 UTC billing period counter를
증가시킵니다. counter update가 included quantity를 넘기면 429로 거부되고 usage insert도 rollback됩니다.
`ApiKeyUsageQuotaIT`는 FREE quota 1에서 병렬 gateway/explicit usage 요청이 usage 1건과 quota counter
1건만 남기는 시나리오를 검증합니다.

확인할 것:

- plan의 included quantity와 overage policy
- `quota_counters`의 organization/metric/month별 used quantity
- 같은 organization의 quota counter update 대기/timeout 여부
- Redis fixed-window rate limit 거부 여부

주의:

- 운영형 quota rollback/reconciliation dashboard, distributed traffic tuning은 별도 과제입니다.

## 6. k6 Mixed Usage Benchmark

`k6/mixed-usage-test.js`와 `scripts/run-full-mixed-evidence.sh`로 local full mixed repeat3 evidence를
수집했습니다. 이 문서는 재현 절차이며, production throughput/latency/error-rate claim으로 해석하지 않습니다.

실제 실행 전 준비:

1. `docker compose up -d`로 PostgreSQL과 Redis를 띄웁니다.
   - 로컬 Redis가 이미 `6379`를 사용 중이면 `docker compose up -d postgres`만 실행하거나 기존 Redis를
     중지합니다.
2. `./gradlew bootRun`으로 로컬 앱을 실행합니다.
   - 5 VU full mixed smoke처럼 branch 실행만 확인할 때는 `GATEWAY_RATE_LIMIT_PER_MINUTE=1000` 등
     테스트 조건에 맞는 rate-limit override를 함께 기록합니다.
   - Redis 포트를 바꿨다면 Spring profile/config와 포트도 evidence에 함께 남깁니다.
3. `POST /api/auth/signup`으로 test user를 만들고 응답의 `accessToken`을 저장합니다.
4. `POST /api/organizations`로 organization을 만들고 응답의 `id`를 저장합니다.
5. `POST /api/organizations/{orgId}/api-keys`로 API key를 만들고 응답의 `rawApiKey`를 저장합니다.
6. PRO subscription/API key와 invoice를 만든 뒤, 선택한 invoice의 `totalAmountMinor`를 확인합니다.
7. 기본 smoke는 `BASE_URL=http://localhost:8080 API_KEY=<rawApiKey> k6 run k6/mixed-usage-test.js`로
   실행합니다. 이 모드는 gateway와 direct usage ingestion branch만 필수로 검증하고, invoice/webhook
   credential이 없으면 optional branch를 `skipped_optional_path_count`로 기록합니다.
8. `K6_VUS=5 K6_DURATION=30s`처럼 부하를 키우려면 앱 실행 시
   `GATEWAY_RATE_LIMIT_PER_MINUTE=1000 ./gradlew bootRun`처럼 gateway rate limit도 테스트 조건에 맞춰
   올립니다.
9. invoice/webhook까지 포함한 full mixed run을 주장하려면 `JWT_TOKEN`, `ORG_ID`, `WEBHOOK_INVOICE_ID`,
   `WEBHOOK_SECRET`, `WEBHOOK_AMOUNT_MINOR`, 필요 시 `INVOICE_PERIOD`를 모두 넘기고
   `K6_REQUIRE_OPTIONAL_PATHS=true`를 설정합니다.
   - `WEBHOOK_AMOUNT_MINOR`는 선택한 invoice의 `totalAmountMinor`와 같아야 합니다.
   - 현재 webhook branch는 같은 invoice에 대해 같은 `providerEventId`를 재사용하므로 fresh payment
     throughput이 아니라 duplicate/idempotency smoke로 해석합니다.

full mixed branch guard 예시:

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<accessToken> \
ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=<webhookSecret> \
WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> \
K6_REQUIRE_OPTIONAL_PATHS=true \
K6_VUS=1 \
K6_DURATION=30s \
k6 run k6/mixed-usage-test.js
```

반복 evidence capture를 남길 때는 아래 스크립트를 사용합니다. 이 스크립트는 `K6_REQUIRE_OPTIONAL_PATHS=true`를
강제하고, run마다 k6 summary/console, 접근 가능한 경우 `/actuator/prometheus` before/after scrape,
sanitized metadata를 `docs/evidence/k6/full-mixed-<timestamp>/` 아래에 저장합니다.

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<accessToken> \
ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=<webhookSecret> \
WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> \
K6_RUNS=3 \
K6_VUS=5 \
K6_DURATION=30s \
scripts/run-full-mixed-evidence.sh
```

스크립트가 확인하는 guard:

- checks rate 100%
- HTTP failure rate 0
- invoice / webhook branch count 1회 이상
- optional branch skip 0건
- Prometheus scrape는 best-effort. endpoint가 인증에 막히면 unavailable note를 남기고 k6 검증은 계속 진행

summary guard는 `scripts/validate-k6-full-mixed-summary.mjs`가 검사합니다. 이 validator는 k6
`--summary-export` artifact의 `value/count` metric shape를 기준으로 성공/실패를 판단합니다.
capture 디렉터리 전체는 `scripts/summarize-full-mixed-evidence.mjs docs/evidence/k6/full-mixed-<timestamp>/`로
검산합니다. 이 명령은 `capture-summary.json`에 run별 guard 통과 여부와 branch count만 남기며,
throughput/latency/error-rate 집계나 benchmark 승격은 하지 않습니다.

주의:

- `API_KEY=replace-with-created-api-key`는 유효한 benchmark 입력이 아닙니다.
- `K6_REQUIRE_OPTIONAL_PATHS=true`에서는 `invoice_path_count > 0`, `webhook_path_count > 0`,
  `skipped_optional_path_count == 0` threshold가 추가됩니다.
- `rawApiKey`는 생성 응답에서만 반환됩니다.
- API key를 만들지 않은 상태의 401/403 응답은 성능 측정 결과로 기록하지 않습니다.
- 같은 이메일로 signup을 재실행하면 conflict가 날 수 있으므로 `k6-local-$(date +%Y%m%d%H%M%S)@example.com`
  같은 고유 이메일을 사용합니다.
- 기본 gateway rate limit은 분당 60회입니다. rate limit에 막힌 429 응답은 gateway 처리 성능 수치가
  아니라 rate-limit 조건 위반으로 분리합니다.
- `scripts/run-full-mixed-evidence.sh`가 artifact를 만들더라도 review 전에는 public throughput/latency
  benchmark로 승격하지 않습니다.

기록 전 확인할 것:

- 실행 환경
- throughput
- p50 / p95 / p99 latency
- error rate
- duplicate/conflict count (`idempotency.conflicts`, `payment.webhooks.conflicts`)
- quota exceeded / rate limited count (`quota.exceeded`, `gateway.rate_limited`)
- gateway request count (`gateway.requests`)
- ledger group count (`ledger.groups.created`)

실제 실행 전에는 README와 `docs/PERF_RESULT.md`에 측정 완료 수치를 추가하지 않습니다.

## 운영 증거 승격 경계

운영 증거로 승격하려면 각 run마다 아래 artifact를 남깁니다.

- scheduler 실행 시각, 대상 period, 성공 organization 수, 실패 organization id 목록
- quota counter와 `usage_events` reconciliation query 결과
- Prometheus scrape 가능 여부와 관련 metric sample
- dashboard, tracing, SLO, alert rule 설정 파일과 캡처/쿼리 결과

위 artifact가 생기기 전까지 dashboard, tracing, SLO, alert rule, quota reconciliation 운영 증거는
`추가 측정 예정`으로 둡니다. Micrometer counter 등록과 actuator 노출 설정은 운영 증거가 아니라
로컬 검증 준비 상태로만 해석합니다.
