# Limitations

이 문서는 현재 repo가 아직 주장하지 않는 것을 명확히 분리합니다. 새 수치나 운영 claim은
실제 구현과 검증이 끝난 뒤에만 추가합니다.

## 현재 주장하지 않는 것

| 항목 | 현재 상태 |
| --- | --- |
| 운영 성능 주장 | 주장하지 않음 |
| gateway + usage local smoke throughput / latency | local smoke 결과는 있으나 benchmark로 공개하지 않음 |
| invoice/webhook branch throughput / latency | full mixed 5 VU local smoke에서 branch 실행과 checks 150/150을 확인했지만 benchmark로 공개하지 않음 |
| full mixed benchmark 결과 | `scripts/run-full-mixed-evidence.sh`로 반복 artifact를 수집할 준비는 했지만, 아직 review된 반복 benchmark 결과는 추가 측정 예정 |
| Invoice scheduler operations | `MonthlyInvoiceScheduler`는 이전 월 invoice idempotency와 per-run organization failure isolation을 검증했습니다. distributed lock, partitioned batch, persistent retry table, 운영 스케줄 관리는 아직 주장하지 않습니다. |
| Quota reservation scope | FREE처럼 overage를 허용하지 않는 plan에서 gateway mock completion과 explicit usage ingestion은 `quota_counters` 월별 counter를 같은 transaction에서 증가시켜 병렬 quota 초과를 막는 시나리오를 검증했습니다. explicit usage의 `occurredAt` 기준 UTC 월별 counter 분리도 검증했지만, 운영형 quota reconciliation dashboard나 distributed traffic tuning은 아직 주장하지 않습니다. |
| Refund accounting compliance | captured payment amount를 넘는 refund는 차단하고 partial refund별 append-only reversal ledger와 original ledger group 추적을 검증했습니다. 실제 회계 compliance는 아직 주장하지 않습니다. |
| Audit sanitizer | caller가 안전한 metadata를 넘긴다는 전제 |
| Alerting / dashboard / tracing / SLO | low-cardinality metric counter 등록 수준 |

## 다음 개선 우선순위

1. `MonthlyInvoiceScheduler`에 distributed lock, partitioned batch, persistent retry table을 추가합니다.
2. `quota_counters`를 운영형 reconciliation dashboard, rollback audit, distributed traffic tuning까지 확장합니다.
3. `payment.refunded` 외에 credit adjustment와 부분 환불 배분 정책을 별도 ledger group 정책으로 확장합니다.
4. `scripts/run-full-mixed-evidence.sh`로 invoice/webhook branch까지 포함한 mixed benchmark를 반복 실행하고, 환경, throughput, latency, error rate를 `docs/PERF_RESULT.md`에 기록합니다.

## 면접에서 안전하게 말할 문장

> 이 프로젝트는 SaaS 과금에서 깨지기 쉬운 tenant isolation, usage idempotency, webhook duplicate,
> gateway retry idempotency, append-only ledger, 월별 invoice scheduler idempotency를 검증한 포트폴리오입니다. 다만
> 운영형 scheduler lock, quota reconciliation dashboard, observability, 반복 가능한 full mixed benchmark 결과는 아직 추가
> 측정 예정으로 분리했습니다.
