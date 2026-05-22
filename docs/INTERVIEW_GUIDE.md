# Interview Guide

## 30초 요약

멀티테넌트 SaaS 과금에서 API key 원문 미저장, tenant isolation, usage idempotency, webhook duplicate,
invoice idempotency, append-only ledger, audit log를 검증한 Spring Boot 백엔드입니다. 성능 benchmark는
아직 추가 측정 예정으로 분리했습니다.

## 예상 질문과 답변 포인트

| 질문 | 답변 포인트 |
| --- | --- |
| API key 원문을 저장하지 않으면 인증은 어떻게 하나요? | 생성 시 raw key는 1회 반환하고 DB에는 prefix/hash만 저장합니다. 요청 key를 hash해 비교합니다. |
| 같은 usage event가 재시도되면 어떻게 하나요? | organization + idempotency key unique scope와 request hash를 비교해 같은 payload는 duplicate, 다른 payload는 conflict로 처리합니다. |
| gateway retry와 explicit usage ingestion key는 충돌하지 않나요? | gateway 호출은 `gateway:` scope를 붙여 explicit usage key와 분리합니다. |
| quota를 초과하는 병렬 요청은 어떻게 막나요? | gateway mock completion과 explicit usage ingestion 모두 idempotency insert 이후 같은 transaction에서 `quota_counters` 월별 counter를 증가시킵니다. counter update가 included quantity를 넘기면 429로 거부하고 usage insert도 rollback됩니다. explicit usage는 `occurredAt` 기준 UTC month별 counter 분리도 검증했습니다. |
| Redis 장애 시 rate limit은 어떻게 되나요? | abuse prevention을 우선해 fail-closed로 503을 반환합니다. `QuotaServiceTest`로 고정했습니다. |
| webhook duplicate와 conflict는 어떻게 구분하나요? | provider event id를 먼저 reserve하고 payload hash가 같으면 duplicate, 다르면 conflict로 처리합니다. |
| ledger row를 수정해도 되나요? | 수정/삭제하지 않습니다. refund는 captured payment의 ledger group을 `original_transaction_group_id`로 참조하는 reversal group을 append합니다. 부분 환불도 captured amount 안에서 별도 reversal group으로 남기도록 검증했습니다. |

## 피해야 할 표현

- 실제 PG 연동 또는 accounting compliance를 주장하지 않습니다.
- production scheduler lock, quota reconciliation dashboard, distributed traffic tuning이 완성됐다고 말하지 않습니다.
- invoice/webhook 포함 full mixed benchmark throughput/latency를 측정 완료로 말하지 않습니다.
