# Limitations

이 문서는 현재 repo가 아직 주장하지 않는 것을 명확히 분리합니다. 새 수치나 운영 claim은
실제 구현과 검증이 끝난 뒤에만 추가합니다.

## 현재 주장하지 않는 것

| 항목 | 현재 상태 |
| --- | --- |
| 운영 성능 주장 | 주장하지 않음 |
| gateway + usage local smoke throughput / latency | local smoke 결과는 있으나 benchmark로 공개하지 않음 |
| invoice/webhook branch throughput / latency | full mixed local repeat3에서 branch 실행과 checks 150/150/run을 확인했지만 claim boundary: production benchmark로 공개하지 않음 |
| full mixed local repeat3 | `docs/evidence/k6/full-mixed-20260523T015029Z/`에 5 VU, 30s, repeat3 artifact를 기록 |
| Invoice scheduler operations | `MonthlyInvoiceScheduler`는 이전 월 invoice idempotency와 per-run organization failure isolation을 검증했습니다. distributed lock, partitioned batch, persistent retry table, 운영 스케줄 관리는 아직 주장하지 않습니다. |
| Quota reservation scope | FREE처럼 overage를 허용하지 않는 plan에서 gateway mock completion과 explicit usage ingestion은 `quota_counters` 월별 counter를 같은 transaction에서 증가시켜 병렬 quota 초과를 막는 시나리오를 검증했습니다. explicit usage의 `occurredAt` 기준 UTC 월별 counter 분리도 검증했지만, 운영형 quota reconciliation dashboard나 distributed traffic tuning은 아직 주장하지 않습니다. |
| Refund accounting compliance | captured payment amount를 넘는 refund는 차단하고 partial refund별 append-only reversal ledger와 original ledger group 추적을 검증했습니다. 실제 회계 compliance는 아직 주장하지 않습니다. |
| Alerting / dashboard / tracing / SLO | low-cardinality metric counter 등록 수준 |

## 다음 개선 우선순위

1. `MonthlyInvoiceScheduler`에 distributed lock, partitioned batch, persistent retry table을 추가합니다.
2. `quota_counters`를 운영형 reconciliation dashboard, rollback audit, distributed traffic tuning까지 확장합니다.
3. `payment.refunded` 외에 credit adjustment와 부분 환불 배분 정책을 별도 ledger group 정책으로 확장합니다.
4. full mixed repeat3를 더 큰 dataset과 고정 실행 환경에서 재측정하고, claim boundary: production claim 가능 여부를 별도로 검토합니다.

## 면접에서 안전하게 말할 문장

> 이 프로젝트는 SaaS 과금에서 깨지기 쉬운 tenant isolation, usage idempotency, webhook duplicate,
> gateway retry idempotency, append-only ledger, 월별 invoice scheduler idempotency를 검증한 포트폴리오입니다. 다만
> 운영형 scheduler lock, quota reconciliation dashboard, observability, claim boundary: production benchmark 결과는 아직 추가 측정
> 예정으로 분리했습니다. full mixed local repeat3는 별도 artifact로만 설명합니다.
