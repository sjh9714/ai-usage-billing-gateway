# AI Usage Billing Gateway

![CI](https://github.com/sjh9714/ai-usage-billing-gateway/actions/workflows/ci.yml/badge.svg)

멀티테넌트 SaaS에서 **AI/API gateway 사용량을 수집하고 과금 흐름으로 연결하는 Spring Boot 백엔드**입니다.

단순 결제 CRUD가 아니라, SaaS 과금 시스템에서 깨지기 쉬운
**API Key 보안, retry idempotency, quota reservation, webhook duplicate delivery,
refund ledger, audit secret hygiene**를 코드와 테스트로 검증하는 데 초점을 둡니다.

## 30초 요약

- **API Key 보안**:
  raw key는 생성 응답에서 1회만 반환하고 DB에는 `keyPrefix`와 hash만 저장합니다.
  audit metadata에도 raw key가 남지 않도록 sanitizer를 둡니다.
- **Usage/Gateway idempotency**:
  `organizationId + Idempotency-Key` scope와 request hash로 duplicate/conflict를 구분합니다.
- **Quota reservation**:
  usage insert와 같은 transaction에서 `quota_counters` 월별 counter를 증가시킵니다.
  FREE plan 같은 non-overage 경계의 동시 초과를 막는 시나리오를 검증했습니다.
- **Invoice/Webhook/Refund ledger**:
  invoice generation, payment webhook duplicate/conflict, captured amount 초과 refund 차단을 검증했습니다.
  partial refund별 reversal ledger group과 append-only ledger invariant도 분리했습니다.
- **Audit sanitizer**:
  API key, authorization, token, secret, password, signature, cookie 계열 key를 `[REDACTED]` 처리합니다.
- **Full mixed repeat3**:
  local `5 VU`, `30s`, repeat3에서 gateway/usage/invoice/webhook branch가 모두 실행됐습니다.
  checks `150/150` 및 HTTP failure `0/150`을 run별로 확인했습니다.

> Claim boundary: 이 README는 실제 AI provider 연동, 실제 PG 연동,
> production throughput/latency/error-rate benchmark 성능을 주장하지 않습니다.
> full mixed repeat3는 local evidence이며, 운영 성능 수치는 `추가 측정 예정`으로 분리합니다.

---

## 전체 아키텍처

![AI Usage Billing Gateway 전체 아키텍처](docs/assets/architecture/overall-architecture.svg)

이 다이어그램은 구현된 핵심 흐름과 검증 대상 경계를 설명하기 위한 단순화된 구조도이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.

| 핵심 설계 판단 | 이유 | 검증 상태 |
| --- | --- | --- |
| raw API key 미저장 | 생성 응답 이후에는 prefix/hash만 남겨 key 유출 위험과 audit 노출 위험을 줄입니다. | 시나리오 검증 |
| usage/gateway idempotency 분리 | gateway retry와 명시적 usage ingestion이 같은 idempotency key로 충돌하지 않게 합니다. | 시나리오 검증 |
| quota reservation과 usage 저장 결합 | usage 기록과 월별 counter 증가가 서로 어긋나는 상황을 줄입니다. | 시나리오 검증 |
| invoice/webhook/ledger 경계 분리 | invoice 생성, provider webhook, append-only ledger를 각각 재시도 가능한 경계로 둡니다. | 시나리오 검증 |
| audit metadata sanitizer | secret 계열 key가 audit log에 남지 않도록 저장 직전에 redaction합니다. | 시나리오 검증 |

상세 설명은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)에 있습니다.
README에는 `overall-architecture.svg`를 기준으로 노출하고, drawio 파일은 편집 참고 자산으로 보관합니다.

---

## 핵심 문제

| 문제 | 설계 대응 | 검증 상태 |
| --- | --- | --- |
| 다른 organization의 billing/usage 데이터 접근 | organization membership 기반 `TenantAccessService`로 organization-scoped API 접근 제어 | 시나리오 검증 |
| API Key 원문 저장 시 유출 위험 | raw key 1회 반환, DB에는 `keyPrefix`와 hash 저장 | 시나리오 검증 |
| usage/gateway retry 중복 과금 | `organizationId + Idempotency-Key` unique scope와 request hash 비교 | 시나리오 검증 |
| 같은 idempotency key의 다른 payload | 기존 request hash와 다르면 `409 Conflict` | 시나리오 검증 |
| quota 초과 동시 요청 | usage insert와 같은 transaction에서 `quota_counters` 월별 reservation | 시나리오 검증 |
| API key 단위 남용 | Redis fixed-window rate limit, Redis 장애 시 fail-closed `503` | 시나리오 검증 |
| invoice generation 재실행 | `organizationId + billingPeriod` 기준 idempotent invoice generation | 시나리오 검증 |
| payment webhook 중복 delivery | `providerEventId` reserve와 payload hash 비교로 duplicate/conflict 처리 | 시나리오 검증 |
| refund가 captured amount를 초과할 위험 | captured balance 확인 후 partial refund별 append-only reversal ledger group 기록 | 시나리오 검증 |
| 금액 상태를 status update만으로 설명하기 어려움 | invoice/payment/refund 흐름을 append-only ledger entry로 기록 | 시나리오 검증 |
| audit log에 secret이 남을 위험 | secret 계열 metadata key를 저장 직전 `[REDACTED]` 처리 | 시나리오 검증 |
| README가 구현보다 과장될 위험 | `측정 완료 / 시나리오 검증 / 추가 측정 예정`을 분리 | 계속 적용 |

## 빠른 근거 링크

| 보고 싶은 것 | 링크 |
| --- | --- |
| 설계 상세 | [docs/DESIGN.md](docs/DESIGN.md) |
| 테스트가 지지하는 claim | [docs/TESTING.md](docs/TESTING.md) |
| 성능/부하 해석 기준 | [docs/PERF_RESULT.md](docs/PERF_RESULT.md) |
| full mixed repeat3 evidence | [repeat3 문서][full-mixed-repeat3] |
| 아직 주장하지 않는 것 | [docs/LIMITATIONS.md](docs/LIMITATIONS.md) |

---

## 아키텍처 요약

```text
User APIs
  └─ JWT Authentication
      └─ Organization / Membership / RBAC
          ├─ API Key Management
          ├─ Subscription / Plan
          ├─ Invoice Generation
          ├─ Ledger
          └─ Audit Log

Gateway / Usage APIs
  └─ X-API-Key Authentication
      ├─ Gateway Retry Idempotency
      ├─ Usage Event Ingestion + Monthly Quota Reservation
      ├─ Redis Rate Limit
      └─ Mock AI Gateway Response

Payment Provider Mock
  └─ Webhook Signature Verification
      ├─ Webhook Event Idempotency
      ├─ Payment Status Update
      ├─ Ledger Entry Creation
      └─ Audit Log
```

---

## 주요 흐름

[full-mixed-repeat3]: docs/evidence/FULL_MIXED_REPEAT3_2026-05-23.md

### 1. API Key 발급과 Gateway 호출

```text
User signup/login
→ organization 생성
→ API key 생성
  - raw key는 응답으로 1회만 반환
  - DB에는 key prefix와 hash만 저장
→ X-API-Key로 /v1/gateway/mock-completion 호출
→ Idempotency-Key로 gateway retry 중복 확인
→ usage event 기록 + 월별 quota counter reservation
→ Redis fixed-window rate limit 검사
→ mock AI response 반환
```

API Key는 다음 형태로 생성됩니다.

```text
ak_<prefix>_<secret>
```

DB에는 raw key를 저장하지 않습니다.

```text
key_prefix = <prefix>
key_hash   = sha256(rawApiKey)
```

---

### 2. Usage Event Idempotency

명시적 사용량 적재 API와 gateway mock 호출은 `Idempotency-Key`를 요구합니다.

```http
POST /api/usage/events
X-API-Key: ak_xxxxxxxx_secret
Idempotency-Key: usage-2026-05-14-001
```

```json
{
  "metric": "REQUEST",
  "quantity": 1,
  "occurredAt": "2026-05-14T00:00:00Z",
  "metadata": {
    "route": "mock-completion"
  }
}
```

idempotency 판단에는 다음 값이 포함됩니다.

```text
metric
quantity
occurredAt
metadata
```

처리 정책:

| 상황 | 결과 |
| --- | --- |
| 같은 organization + 같은 idempotency key + 같은 payload | 기존 usage event 반환, duplicate=true |
| 같은 organization + 같은 idempotency key + 다른 payload | 409 Conflict |
| 다른 organization의 같은 idempotency key | 독립적으로 처리 |

`/v1/gateway/mock-completion`은 client가 보낸 `Idempotency-Key`를 `gateway:` scope로 저장해
명시적 usage ingestion key와 충돌하지 않게 분리합니다. 같은 prompt retry는 duplicate response로
수렴하고, 같은 key의 다른 prompt는 `409 Conflict`로 거절합니다. duplicate retry는 quota/rate check
전에 확인해 timeout retry가 이미 기록된 사용량 때문에 `429`로 바뀌지 않게 했습니다.

> 이 프로젝트는 DB unique constraint 기반 idempotent storage를 구현합니다. 분산 시스템 전체의 exactly-once 처리를 주장하지 않습니다.

---

### 3. Quota와 Rate Limit

Gateway 호출은 두 경계를 통과해야 합니다.

```text
1. usage event insert와 같은 transaction 안의 monthly quota reservation
2. API key 단위 Redis fixed-window rate limit
```

명시적 usage ingestion도 새 event를 저장한 뒤 같은 transaction 안에서 `quota_counters` 월별 counter를
증가시킵니다. quota 초과 시 usage insert까지 rollback되며, duplicate idempotency retry는 기존 event로
수렴하므로 quota boundary에서 이미 기록된 같은 key가 `429`로 바뀌지 않습니다.

요금제 예시:

| Plan | Included Requests | Overage |
| --- | ---: | --- |
| FREE | 10,000 / month | 허용하지 않음 |
| PRO | 100,000 / month | 허용 |
| BUSINESS | 1,000,000 / month | 허용 |

Redis rate limit은 API key 단위 fixed-window 방식입니다.

```text
rate:api-key:{apiKeyId}:{epochMinute}
```

Redis 장애 시에는 fail-closed 정책을 사용합니다.

```text
Rate limiter unavailable → 503 Service Unavailable
```

---

### 4. Invoice Generation

월별 invoice는 organization과 billing period 기준으로 idempotent하게 생성됩니다.

```http
POST /api/organizations/{orgId}/invoices/generate?period=2026-05
Authorization: Bearer <JWT>
```

계산 흐름:

```text
usage_events
→ monthly usage sum
→ subscription plan lookup
→ included quota 적용
→ overage 계산
→ invoice 생성
→ invoice_items 생성
→ ledger entries 생성
→ audit log 기록
```

중복 실행 정책:

| 상황 | 결과 |
| --- | --- |
| 같은 organization + 같은 billing period 최초 실행 | invoice 생성 |
| 같은 organization + 같은 billing period 재실행 | 기존 invoice 반환, duplicate=true |

---

### 5. Payment Webhook

Mock payment provider webhook은 HMAC-SHA256 signature를 검증합니다.

```http
POST /api/webhooks/payments
X-Webhook-Signature: <hmac>
```

지원하는 event type:

```text
payment.succeeded
payment.failed
payment.refunded
```

Webhook idempotency 정책:

| 상황 | 결과 |
| --- | --- |
| 새로운 providerEventId | webhook event reserve 후 처리 |
| 같은 providerEventId + 같은 payload | duplicate=true |
| 같은 providerEventId + 다른 payload | 409 Conflict |

동시 중복 delivery를 고려해 `providerEventId`를 먼저 reserve합니다.

```sql
INSERT ... ON CONFLICT (provider_event_id) DO NOTHING
```

---

### 6. Ledger

금액 상태는 invoice/payment status만으로 설명하지 않고, ledger entry로 남깁니다.

Invoice 발행 예시:

```text
DEBIT   ACCOUNTS_RECEIVABLE   75 USD
CREDIT  REVENUE               75 USD
```

Payment 성공 예시:

```text
DEBIT   CASH                  75 USD
CREDIT  ACCOUNTS_RECEIVABLE  75 USD
```

Ledger entry group은 service layer에서 다음 invariant를 검증합니다.

```text
debit sum == credit sum
single currency
positive amount only
```

> 이 프로젝트의 ledger는 포트폴리오용 simplified balanced-entry model입니다. 회계 기준 준수나 실제 정산 시스템 수준의 accounting compliance를 주장하지 않습니다.

---

### 7. Audit Log

보안/과금 민감 이벤트는 audit log로 기록합니다.

기록 대상 예시:

```text
ORGANIZATION_CREATED
MEMBER_ADDED
API_KEY_CREATED
API_KEY_REVOKED
SUBSCRIPTION_CHANGED
INVOICE_GENERATED
PAYMENT_WEBHOOK_PROCESSED
LEDGER_ENTRY_GROUP_CREATED
```

API key 관련 audit metadata에는 raw key를 저장하지 않고 key prefix 등 안전한 값만 기록합니다.
`AuditService`는 저장 직전에 `apiKey`, `authorization`, `token`, `secret`, `password`, `signature`,
`cookie` 계열 key를 대소문자 구분 없이 `[REDACTED]`로 치환합니다. 중첩된 Map/List metadata도 unit test로
시나리오 검증했습니다.

---

## 검증한 항목

| 영역 | 상태 | Evidence |
| --- | --- | --- |
| Spring context / Flyway / health endpoint | 시나리오 검증 | `ApplicationContextIT` |
| JWT signup/login | 시나리오 검증 | `AuthTenantSecurityIT` |
| RBAC | 시나리오 검증 | `AuthTenantSecurityIT` |
| Cross-tenant access denial | 시나리오 검증 | `AuthTenantSecurityIT` |
| API Key 원문 1회 반환 | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| API Key hash 저장 | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| API Key revoke | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| Usage event idempotency | 시나리오 검증 | `ApiKeyUsageQuotaIT`, `UsageServiceTest` |
| 같은 idempotency key의 다른 payload conflict | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| Gateway retry idempotency | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| usage request hash에 `occurredAt` 포함 | 시나리오 검증 | `UsageServiceTest` |
| Gateway timestamp consistency | 시나리오 검증 | gateway request 시각을 한 번 캡처해 usage record와 quota counter에 같은 `occurredAt`을 전달하는지 검증 |
| Gateway/explicit quota counter reservation | 시나리오 검증 | 병렬 초과 차단, duplicate retry 선처리, `occurredAt` 기준 UTC 월별 counter 분리 |
| Redis rate limit | 시나리오 검증 | `ApiKeyUsageQuotaIT` |
| Invoice generation idempotency | 시나리오 검증 | `BillingPaymentLedgerAuditIT` |
| Monthly invoice scheduler idempotency | 시나리오 검증 | `MonthlyInvoiceSchedulerIT` |
| Payment webhook signature validation | 시나리오 검증 | `BillingPaymentLedgerAuditIT` |
| Payment webhook duplicate/amount guard | 시나리오 검증 | duplicate/conflict, invoice mismatch, 두 번째 capture 차단, refund balance cap 검증 |
| Webhook duplicate race fallback | 시나리오 검증 | `PaymentWebhookServiceTest` |
| Ledger debit/credit balance | 시나리오 검증 | `BillingPaymentLedgerAuditIT`, `LedgerServiceTest` |
| Refund reversal ledger | 시나리오 검증 | balanced reversal, partial refund별 group, duplicate idempotency, captured amount 초과 방지 검증 |
| Ledger 단일 currency / 양수 amount invariant | 시나리오 검증 | `LedgerServiceTest` |
| Audit log secret hygiene | 시나리오 검증 | raw API key 미저장과 audit metadata sanitizer 테스트 |
| 혼합 사용량 부하 테스트 | 측정 완료 / local repeat3 | 5 VU, 30s, repeat3에서 checks 150/150, HTTP failure 0/150, invoice/webhook branch 각 6회/run 확인 |
| 운영 성능 주장 | 추가 측정 예정 | 주장하지 않음 |

---

## API 요약

### Auth

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/auth/signup` | 회원가입 |
| `POST` | `/api/auth/login` | 로그인, JWT 발급 |

### Organization

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/organizations` | organization 생성 |
| `GET` | `/api/organizations` | 내 organization 목록 |
| `GET` | `/api/organizations/{orgId}` | organization 조회 |
| `POST` | `/api/organizations/{orgId}/members` | member 추가 |
| `PUT` | `/api/organizations/{orgId}/subscription` | subscription plan 변경 |

### API Key

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/organizations/{orgId}/api-keys` | API key 생성, raw key 1회 반환 |
| `GET` | `/api/organizations/{orgId}/api-keys` | API key 목록 조회, raw key 미포함 |
| `DELETE` | `/api/organizations/{orgId}/api-keys/{keyId}` | API key revoke |

### Gateway / Usage

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/v1/gateway/mock-completion` | `X-API-Key`, `Idempotency-Key` | mock AI gateway 호출 |
| `POST` | `/api/usage/events` | `X-API-Key`, `Idempotency-Key` | 명시적 usage event 적재 |

### Billing / Payment

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/organizations/{orgId}/invoices/generate?period=YYYY-MM` | invoice 생성 |
| `POST` | `/api/webhooks/payments` | mock payment webhook 수신 |

### Actuator

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/actuator/health` | health check |
| `GET` | `/actuator/prometheus` | local Prometheus-format metrics. 운영 노출에는 인증/네트워크 정책 필요 |

> Micrometer metrics는 등록되어 있습니다. 다만 Prometheus scraping을 운영 수준으로 노출하려면 별도 인증/네트워크 정책 구성이 필요합니다.

---

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Gradle Kotlin DSL |
| Database | PostgreSQL |
| Migration | Flyway |
| Cache / Rate Limit | Redis |
| Security | Spring Security, JWT, API Key |
| Persistence | Spring Data JPA, Hibernate validate |
| Observability | Spring Boot Actuator, Micrometer, Prometheus registry |
| Test | JUnit 5, Spring Security Test, Testcontainers, AssertJ, Mockito |
| Load Script | k6 |
| Infra | Docker Compose |
| CI | GitHub Actions |

---

## 실행 방법

### 1. 인프라 실행

```bash
docker compose up -d
```

로컬 Redis가 이미 `6379`를 사용 중이면 compose Redis가 포트 충돌로 뜨지 않을 수 있습니다.
이 경우 기존 Redis를 쓰고 `docker compose up -d postgres`만 실행하거나, 로컬 Redis를 중지한 뒤
compose 전체를 올립니다.

### 2. 애플리케이션 실행

```bash
./gradlew bootRun
```

기본 로컬 설정은 다음 파일에 있습니다.

```text
src/main/resources/application.yml
```

### 3. 테스트 실행

Testcontainers 기반 테스트는 Docker가 필요합니다.

```bash
./gradlew test --no-daemon
./gradlew build --no-daemon
```

### 4. k6 script inspect

```bash
k6 inspect k6/mixed-usage-test.js
k6 inspect -e K6_REQUIRE_OPTIONAL_PATHS=true k6/mixed-usage-test.js
```

### 5. k6용 API Key 생성

`k6/mixed-usage-test.js`는 fixture key나 fake key를 사용하지 않습니다. 로컬
앱을 띄운 뒤 실제 API로 user, organization, API key를 만든 다음, 응답의
`rawApiKey`를 `API_KEY`로 넘겨야 합니다.

```bash
curl -s -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"k6-local-$(date +%Y%m%d%H%M%S)@example.com\",\"password\":\"password123\"}"
```

응답의 `accessToken`을 `TOKEN`으로 저장한 뒤 organization을 생성합니다.

```bash
curl -s -X POST http://localhost:8080/api/organizations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"k6-local-org"}'
```

응답의 `id`를 `ORG_ID`로 저장한 뒤 API key를 생성합니다.

```bash
curl -s -X POST "http://localhost:8080/api/organizations/$ORG_ID/api-keys" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"k6-local-key"}'
```

응답의 `rawApiKey`를 `API_KEY`로 사용합니다. `rawApiKey`는 생성 응답에서만
반환되므로 재확인이 필요하면 새 key를 만들어야 합니다.

### 6. k6 실행

`k6/mixed-usage-test.js`는 기본적으로 gateway 호출과 직접 usage ingestion 경로를 확인하고,
`JWT_TOKEN`, `ORG_ID`, `WEBHOOK_INVOICE_ID`, `WEBHOOK_SECRET`을 제공하면 invoice generation과
payment webhook branch도 함께 실행할 수 있습니다.

기본 k6 설정은 local smoke용 `K6_VUS=1`, `K6_DURATION=30s`입니다. 더 큰 부하를 줄 때는
`gateway.rate-limit-per-minute` 또는 `GATEWAY_RATE_LIMIT_PER_MINUTE` 설정을 함께 올리고,
실행 환경과 결과를 `docs/PERF_RESULT.md`에 기록해야 합니다.

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
k6 run k6/mixed-usage-test.js
```

invoice/webhook branch까지 포함하려면 인증 context와 webhook 대상 invoice를 함께 넘깁니다.

```bash
GATEWAY_RATE_LIMIT_PER_MINUTE=1000 ./gradlew bootRun

BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<jwt> \
ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=<localWebhookSecret> \
WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> \
K6_REQUIRE_OPTIONAL_PATHS=true \
K6_VUS=5 \
K6_DURATION=30s \
k6 run k6/mixed-usage-test.js
```

`WEBHOOK_AMOUNT_MINOR`는 선택한 invoice의 `totalAmountMinor`와 같아야 합니다. 현재 script는 같은
invoice에 대해 같은 `providerEventId`를 재사용하므로 webhook branch는 fresh payment throughput이 아니라
duplicate delivery/idempotency smoke로 해석합니다.

반복 evidence artifact를 남길 때는 capture script를 사용합니다. 이 스크립트는 k6 summary/console,
접근 가능한 경우 Prometheus before/after sample, sanitized metadata를
`docs/evidence/k6/full-mixed-<timestamp>/`에 저장합니다.
각 run의 `summary.json`은 `scripts/validate-k6-full-mixed-summary.mjs`로 checks 100%, HTTP failure 0,
invoice/webhook branch 실행, optional skip 0 조건을 확인합니다.
2026-05-23 local repeat3 artifact는 측정 evidence로 기록했지만 production throughput/latency claim으로는
사용하지 않습니다.

```bash
BASE_URL=http://localhost:8080 \
API_KEY=<rawApiKey> \
JWT_TOKEN=<jwt> \
ORG_ID=<organizationId> \
WEBHOOK_INVOICE_ID=<invoiceId> \
WEBHOOK_SECRET=<localWebhookSecret> \
WEBHOOK_AMOUNT_MINOR=<invoiceTotalAmountMinor> \
K6_RUNS=3 \
K6_VUS=5 \
K6_DURATION=30s \
scripts/run-full-mixed-evidence.sh
```

---

## 성능 결과

현재 공개 가능한 production throughput / latency / error-rate benchmark 수치는 기록하지 않았습니다.

| 항목 | 상태 |
| --- | --- |
| gateway + usage local smoke | 시나리오 검증 |
| invoice/webhook branch | full mixed 5 VU smoke에서 checks 150/150 확인 |
| full mixed local repeat3 | 측정 완료: 5 VU, 30s, repeat3, checks 150/150/run, HTTP failure 0/150/run |
| 처리량 / latency / error rate | local repeat3 수치는 artifact로 공개하되 production benchmark로 승격 안 함 |
| 운영 성능 주장 | 주장하지 않음 |

자세한 내용은 [docs/PERF_RESULT.md](docs/PERF_RESULT.md)를 참고하세요.

성능 수치를 추가할 때는 반드시 아래 정보를 함께 기록합니다.

```text
- 실행 날짜
- hardware
- JVM version
- PostgreSQL/Redis 실행 환경
- dataset size
- command
- throughput
- p95 / p99 latency
- error rate
```

---

## 한계

| 항목 | 현재 한계 |
| --- | --- |
| AI provider | 실제 AI provider 호출이 아니라 mock response입니다. |
| Payment provider | 실제 PG 연동이 아니라 mock webhook입니다. |
| JWT | refresh token, OAuth, SSO, full IAM lifecycle은 구현하지 않았습니다. |
| Quota concurrency | FREE plan의 gateway/explicit usage가 같은 transaction에서 월별 counter를 증가시켜 동시 초과를 막는 시나리오를 검증했습니다. 운영형 tuning은 아직 주장하지 않습니다. |
| Rate limit | Redis fixed-window 방식이라 window boundary burst 가능성이 있습니다. |
| Invoice scheduler | `MonthlyInvoiceScheduler`는 이전 월 invoice를 생성합니다. 대량 batch partitioning, distributed lock, 운영 스케줄 관리는 아직 주장하지 않습니다. |
| Refund accounting | `payment.refunded` webhook은 captured amount 안에서만 append-only reversal ledger를 추가합니다. 실제 회계 compliance는 주장하지 않습니다. |
| Ledger | simplified balanced-entry model입니다. 회계 기준 준수나 실제 정산 compliance를 주장하지 않습니다. |
| Observability | low-cardinality metric counter 등록 수준입니다. alerting, dashboard, tracing, SLO 운영 체계는 별도 과제입니다. |
| Performance | k6 full mixed local repeat3 evidence는 있지만 production benchmark 결과는 추가 측정 예정입니다. |

---

## 문서

| 문서 | 내용 |
| --- | --- |
| [docs/DESIGN.md](docs/DESIGN.md) | tenant isolation, API key auth, idempotency, quota, webhook, ledger, audit 설계 |
| [docs/PERF_RESULT.md](docs/PERF_RESULT.md) | 공개 부하 테스트 결과, 시나리오 검증, 추가 측정 예정 성능 문서 |
| [docs/TESTING.md](docs/TESTING.md) | 테스트가 지지하는 보안/정합성/과금 claim 정리 |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) | invoice 중복 실행, webhook duplicate/conflict, ledger 불균형, quota/rate limit 대응 절차 |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | 아직 주장하지 않는 것과 다음 개선 과제 |
| [docs/evidence/K6_EVIDENCE_MANIFEST.md](docs/evidence/K6_EVIDENCE_MANIFEST.md) | k6 local smoke artifact의 current/superseded 해석 기준 |
| [docs/INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md) | 면접에서 설명할 핵심 질문과 안전한 답변 |
| [docs/STUDY_GUIDE.md](docs/STUDY_GUIDE.md) | 면접 대비 설명 포인트와 안전한 주장 |

---

## 면접에서 설명할 핵심 포인트

```text
이 프로젝트는 멀티테넌트 SaaS 과금 시스템에서 중요한 보안/정합성 문제를 다룹니다.

organization membership 기반으로 tenant isolation을 검증했고,
API key는 raw key를 한 번만 반환하고 hash만 저장했습니다.

usage event는 organizationId + idempotencyKey unique scope와 request hash로
중복 적재와 payload mismatch를 방어했습니다.

invoice generation은 organizationId + billingPeriod 기준으로 idempotent하게 만들었고,
payment webhook은 providerEventId와 payloadHash로 duplicate/conflict를 구분했습니다.

금액 변화는 invoice/payment status만으로 설명하지 않고,
append-only ledger entry로 기록하며 debit/credit balance, 단일 currency, 양수 amount invariant를 검증했습니다.

성능 수치는 local repeat3 evidence와 production benchmark claim을 분리해 문서화했습니다.
```
