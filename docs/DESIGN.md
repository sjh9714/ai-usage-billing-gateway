# Design

## Tenant Isolation

Organization-scoped APIs validate membership through `TenantAccessService`. OWNER and ADMIN can perform billing-sensitive operations, while MEMBER can read organization data but cannot change billing/subscription state.

Organization-scoped tables carry `organization_id` directly where practical. Tests cover cross-tenant denial and MEMBER rejection for admin-only subscription changes.

## Authentication

User-facing APIs use JWT bearer tokens. Gateway and usage APIs use `X-API-Key`.

API keys are generated as `ak_<prefix>_<secret>`. The raw key is returned only at creation time. The database stores `key_prefix` and `key_hash`, never the raw key. Revoked keys are rejected by the API key authentication filter.

## Usage Ingestion And Idempotency

`POST /api/usage/events` requires API key authentication and an `Idempotency-Key` header. The database enforces uniqueness on `(organization_id, idempotency_key)`.

A repeated request with the same payload returns the existing event as a duplicate. The same idempotency key with a different request hash is rejected.

`POST /v1/gateway/mock-completion` also requires `Idempotency-Key`. Gateway calls store usage events with a `gateway:` idempotency key scope so gateway retries do not collide with explicit usage ingestion keys. Existing gateway duplicates are checked before quota/rate enforcement, so a timeout retry can return the duplicate mock response instead of being converted into a quota rejection.

This is idempotent storage through database constraints; it is not an exactly-once distributed processing claim.

## Quota And Rate Limit

Monthly quota reservation stores one row per `organization_id + metric + UTC month` in `quota_counters`.
Usage ingestion first reserves the idempotency key with `insert ... on conflict do nothing`, then increments the
monthly counter in the same transaction. FREE does not allow overage. PRO and BUSINESS allow overage.

Gateway requests also pass a Redis fixed-window rate limit per API key. Redis failure is fail-closed with `503 Service Unavailable`. Fixed-window boundary bursts are a known limitation.

Gateway mock completion captures one request timestamp and uses it for both quota period calculation and usage event storage. This prevents a UTC month-boundary request from being checked against one month and recorded in another.

Explicit `POST /api/usage/events` and gateway mock completion share the same reservation path. Duplicate idempotency
retries resolve to the existing event before counter increments, including quota-boundary retries that arrive while
the first request is committing. This is a correctness counter for the portfolio scenario, not a production-grade
quota operations system with reconciliation dashboards or distributed traffic tuning.

## Invoice Generation

Invoice generation is organization-scoped and admin-only. A unique constraint on `(organization_id, billing_period)` makes generation idempotent.

Invoices are generated from REQUEST usage, the subscription plan, included quantity, base fee, and overage unit amount. Duplicate generation returns the existing invoice.

`MonthlyInvoiceScheduler` can run the same idempotent generation path for active subscriptions and the previous billing period when `billing.invoice-scheduler.enabled=true`. The manual endpoint remains the admin retry path.

## Payment Webhook

`POST /api/webhooks/payments` is public but requires `X-Webhook-Signature`, an HMAC-SHA256 over the raw request body.

`providerEventId` is unique. Duplicate webhook delivery returns a duplicate response without creating another payment or ledger entry. Reusing the same provider event id with a different payload hash is rejected.

`payment.succeeded` must match the invoice amount and currency before the invoice can be marked paid, and a second distinct capture for the same invoice is rejected. Payment webhook processing locks the invoice row while checking captured/refunded balance so parallel refund webhooks cannot exceed the captured amount. `payment.refunded` must use the invoice currency and stay within the captured payment balance.

This is a mock payment provider integration, not a real PG integration.

## Ledger

Ledger entries are append-only. PostgreSQL triggers reject update and delete operations on `ledger_entries`.

Invoice issuance records receivable/revenue entries. Successful payment records cash/receivable entries. Refund
reversal entries keep `original_transaction_group_id` pointing to the captured payment ledger group so the
append-only correction can be traced without mutating the original rows. Entries are balanced by transaction group in
tests.

This is a simplified ledger model and does not claim accounting compliance.

## Audit Log

Audit logs are append-only and organization-scoped. PostgreSQL triggers reject update and delete operations on `audit_logs`.

The implementation records organization creation, member changes, API key creation/revocation, subscription changes, invoice generation, webhook processing, and ledger group creation. Audit metadata stores key prefixes but not raw API keys or secrets. `AuditService` sanitizes metadata at the persistence boundary by redacting sensitive key names case-insensitively, including nested Map/List values.

## Observability

Micrometer counters are registered for usage ingestion, duplicate/conflict outcomes, quota exceeded, gateway request/rate-limit outcomes, API key auth failures, invoice generation, webhook received/duplicate/conflict outcomes, ledger entries/groups, and audit logs.

Metrics intentionally avoid high-cardinality tags such as organization id, user id, invoice id, email, and raw API key.

`/actuator/prometheus` scrape endpoint는 현재 공개 성능 근거로 검증한 대상이 아닙니다. health/info
외 actuator 접근, 인증, 내부망 scrape 정책을 명시한 뒤에만 운영 관측성 근거로 올립니다.

## Limitations

- No real AI provider calls.
- No real payment gateway integration.
- No refresh tokens, OAuth, SSO, or full IAM lifecycle.
- No distributed lock or partitioned batch runner for invoice generation.
- benchmark 수치는 아직 주장하지 않습니다.
