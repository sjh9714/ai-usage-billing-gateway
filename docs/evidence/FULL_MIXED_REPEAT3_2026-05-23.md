# Full Mixed Repeat3 Evidence - 2026-05-23

이 문서는 `scripts/run-full-mixed-evidence.sh`로 생성한 local full mixed repeat3 artifact의 해석을
정리합니다. 실행은 gateway, direct usage ingestion, invoice generation, payment webhook branch를 모두
포함합니다.

운영 성능 주장이 아닙니다. 실행 환경의 CPU, memory, Docker resource, dataset 규모가 고정 문서화되지 않았으므로
이 결과는 local repeat3 측정 evidence로만 사용합니다.

## Artifact

- Directory: `docs/evidence/k6/full-mixed-20260523T015029Z/`
- Script: `scripts/run-full-mixed-evidence.sh`
- k6: `v1.5.0`
- VUs / duration: `5 VU`, `30s`
- Required optional paths: `K6_REQUIRE_OPTIONAL_PATHS=true`
- Gateway rate limit override: `GATEWAY_RATE_LIMIT_PER_MINUTE=1000`
- Branch mix: gateway 70%, direct usage 20%, invoice 5%, webhook 5%
- Webhook interpretation: same invoice + same `providerEventId` duplicate/idempotency path
- Prometheus scrape: best-effort; local `/actuator/prometheus` returned `401`, so unavailable notes were recorded

## Result

| Run | HTTP requests | RPS | HTTP p95 | HTTP avg | Checks | HTTP failed | gateway | usage | invoice | webhook | optional skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| run-1 | 150 | 4.8567 | 44.1454ms | 28.3947ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |
| run-2 | 150 | 4.9255 | 19.8888ms | 14.2052ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |
| run-3 | 150 | 4.9329 | 18.5976ms | 11.7373ms | 150/150 | 0/150 | 107 | 31 | 6 | 6 | 0 |

## Claim boundary

- 측정 완료: local full mixed repeat3에서 checks 100%, HTTP failure 0%, optional branch skip 0을 확인했습니다.
- 측정 완료: gateway, usage, invoice, webhook branch가 세 run 모두 실행됐습니다.
- 주장하지 않음: production throughput, production latency, provider integration performance.
- 주의: webhook branch는 fresh payment throughput이 아니라 duplicate delivery / idempotency smoke입니다.
