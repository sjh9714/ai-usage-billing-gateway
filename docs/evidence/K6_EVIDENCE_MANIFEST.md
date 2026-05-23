# k6 Evidence Manifest

이 문서는 `docs/evidence/k6/` 아래 local smoke / repeat artifact의 해석 기준을 정리합니다. 모든 항목은
local evidence이며, production throughput / latency / error-rate benchmark claim으로 사용하지 않습니다.

## Current evidence

| Artifact | Status | Interpretation |
| --- | --- | --- |
| `mixed-usage-smoke-2026-05-22-summary.json` | current smoke | gateway / direct usage ingestion branch 실행 확인 |
| `mixed-usage-full-20260522154446-summary.json` | current smoke | gateway / usage / invoice / webhook branch 1 VU 실행 확인 |
| `mixed-usage-full-20260522081328-summary.json` | current 5 VU smoke refresh | gateway / usage / invoice / webhook branch 실행, checks 150/150, HTTP failure 0/150 확인 |
| `full-mixed-20260523T015029Z/` | current local repeat3 | 5 VU, 30s, 3 runs, checks 150/150/run, HTTP failure 0/150/run, optional skip 0 확인 |

## Superseded diagnostic artifact

| Artifact | Status | Why it remains |
| --- | --- | --- |
| `mixed-usage-full-20260522080203-summary.json` | superseded diagnostic | 이전 5 VU refresh에서 webhook check 1건 실패를 기록한 artifact입니다. 이후 script가 같은 invoice에 대해 같은 `providerEventId`를 재사용하도록 정리됐고, `mixed-usage-full-20260522081328-summary.json`에서 checks 150/150을 확인했습니다. |

## Claim boundary

- current smoke는 branch 실행 여부와 local idempotency path를 확인하는 증거입니다.
- production benchmark로 올리려면 hardware/JVM/DB/Redis 조건, dataset 규모, p50/p95/p99,
  throughput, error rate, duplicate/conflict/quota/rate-limit count를 함께 고정해 기록해야 합니다.
- 반복 full mixed artifact는 `scripts/run-full-mixed-evidence.sh`로 수집합니다. 이 스크립트가 만드는
  `full-mixed-<timestamp>/` 디렉터리는 k6 summary/console, 접근 가능한 경우 Prometheus before/after sample,
  sanitized metadata를 묶는 capture format입니다. 각 summary는 `scripts/validate-k6-full-mixed-summary.mjs`로
  checks 100%, HTTP failure 0, invoice/webhook branch 실행, optional skip 0 조건을 확인합니다.
- 같은 디렉터리의 `capture-summary.json`은 `scripts/summarize-full-mixed-evidence.mjs`가 생성하는
  machine-readable rollup입니다. run별 summary path, guard 통과 여부, checks rate, HTTP failure rate,
  invoice/webhook branch count, optional skip count, metadata path만 담으며 benchmark 수치를 집계하지 않습니다.
- 2026-05-23 capture는 review 후 local repeat3 측정 evidence로 문서화했습니다. production benchmark로는
  승격하지 않습니다.
- superseded diagnostic artifact는 실패 이력을 숨기지 않기 위해 남겨 두지만, 최신 문서의 성공 evidence로
  참조하지 않습니다.
