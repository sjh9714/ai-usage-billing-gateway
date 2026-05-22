# Mixed Usage Local Smoke Evidence

이 문서는 `k6/mixed-usage-test.js`가 local smoke에서 gateway path와 direct usage ingestion path를 모두
실행하는지 확인한 요약 증거입니다. 정식 부하 테스트 결과가 아니며 throughput, latency, error-rate benchmark로
사용하지 않습니다.

## Command

```bash
BASE_URL=http://localhost:8080 API_KEY=<rawApiKey> K6_VUS=1 K6_DURATION=30s \
k6 run k6/mixed-usage-test.js \
  --summary-export docs/evidence/k6/mixed-usage-smoke-2026-05-22-summary.json \
  2>&1 | tee docs/evidence/k6/mixed-usage-smoke-2026-05-22-console.txt
```

## Scope

- 실제 signup / organization / API key 생성 후 raw API key로 실행
- gateway mock completion path
- direct usage event ingestion path
- invoice / webhook optional branch는 credential이 없으면 skip counter로만 기록

## k6 Summary

```txt
checks_total: 28
checks_succeeded: 28 out of 28
checks_failed: 0 out of 28
gateway_path_count: 24
usage_path_count: 4
skipped_optional_path_count: 2
http_req_failed: 0 out of 28
threshold gateway_path_count count>0: passed
threshold usage_path_count count>0: passed
threshold http_req_failed rate<0.05: passed
```

## Artifacts

- `docs/evidence/k6/mixed-usage-smoke-2026-05-22-summary.json`
- `docs/evidence/k6/mixed-usage-smoke-2026-05-22-console.txt`

## Interpretation

- `gateway_path_count > 0`과 `usage_path_count > 0`을 확인해 기본 local smoke가 두 핵심 ingest path를 모두
  실행합니다.
- `skipped_optional_path_count: 2`는 invoice / webhook optional branch credential을 제공하지 않아 skip된
  경로입니다.
- 이 결과는 branch coverage와 threshold 확인용입니다. p95 latency, throughput, 운영 성능 claim으로 사용하지
  않습니다.
