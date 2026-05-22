# Study Guide

## Interview Narrative

This project shows backend concerns that are easy to miss in CRUD projects:

- tenant isolation at service boundaries
- API key hashing and one-time raw key display
- idempotency through database constraints
- quota and rate limit separation
- webhook signature validation and duplicate handling
- ledger entries for explainable financial state
- audit logs that avoid secret leakage

## Questions To Prepare

- Why is `(organization_id, idempotency_key)` safer than an in-memory duplicate check?
- What can go wrong with fixed-window rate limiting?
- Why should financial flows use ledger entries instead of only mutable statuses?
- How does the design prevent cross-tenant access?
- Which parts are portfolio mocks and which parts are verified?

## Safe Claims

- The repository includes verified integration tests for tenant isolation, API key auth, idempotency, webhook handling, ledger balance, and audit secret hygiene.
- The repository uses a mock AI provider and mock payment provider.
- k6 local smoke evidence는 기록했지만 throughput/latency/error-rate benchmark 결과는 추가 측정 예정으로 둡니다.
