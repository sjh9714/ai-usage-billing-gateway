# Full Mixed Evidence Capture

This directory was produced by `scripts/run-full-mixed-evidence.sh`.

It captures repeated local full mixed smoke artifacts for:

- k6 summary and console output
- Prometheus samples before and after each run when the endpoint is accessible
- sanitized command/environment metadata
- `capture-summary.json` scenario-validation rollup

This artifact is evidence capture only. It does not automatically promote
throughput, latency, or error-rate numbers to a public benchmark claim. Update
`docs/PERF_RESULT.md` only after reviewing repeatability, environment, dataset,
and run-to-run variance.
