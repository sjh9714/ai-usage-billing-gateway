#!/usr/bin/env bash
set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 1
  fi
}

strip_trailing_whitespace() {
  perl -0pi -e 's/[ \t]+(\r?\n)/$1/g; s/[ \t]+\z//' "$1"
}

capture_prometheus() {
  local output_path="$1"
  local unavailable_path="${output_path%.prom}.unavailable.txt"
  local error_path="${unavailable_path}.tmp"

  if curl -fsS "$PROMETHEUS_URL" >"$output_path" 2>"$error_path"; then
    rm -f "$error_path"
    rm -f "$unavailable_path"
    return
  fi

  rm -f "$output_path"
  cat >"$unavailable_path" <<EOF
Prometheus sample unavailable.
URL: ${PROMETHEUS_URL}
Reason: curl failed; the local actuator endpoint may require authentication or network access not used by this evidence run.
curl stderr: $(tr '\n' ' ' <"$error_path" | sed 's/[[:space:]]*$//')
EOF
  rm -f "$error_path"
  strip_trailing_whitespace "$unavailable_path"
}

require_command curl
require_command git
require_command k6
require_command node

required_env_vars=(
  API_KEY
  JWT_TOKEN
  ORG_ID
  WEBHOOK_INVOICE_ID
  WEBHOOK_SECRET
  WEBHOOK_AMOUNT_MINOR
)

for env_name in "${required_env_vars[@]}"; do
  require_env "$env_name"
done

BASE_URL="${BASE_URL:-http://localhost:8080}"
PROMETHEUS_URL="${PROMETHEUS_URL:-${BASE_URL}/actuator/prometheus}"
K6_RUNS="${K6_RUNS:-3}"
K6_VUS="${K6_VUS:-5}"
K6_DURATION="${K6_DURATION:-30s}"
INVOICE_PERIOD="${INVOICE_PERIOD:-$(date -u +%Y-%m)}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${OUT_DIR:-docs/evidence/k6/full-mixed-${timestamp}}"

if ! [[ "$K6_RUNS" =~ ^[1-9][0-9]*$ ]]; then
  echo "K6_RUNS must be a positive integer, got: $K6_RUNS" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

cat >"${OUT_DIR}/metadata.txt" <<EOF
AI Usage Billing full mixed evidence capture
Captured at: ${timestamp}
Git commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)
Git status:
$(git status --short)

Inputs:
BASE_URL=${BASE_URL}
PROMETHEUS_URL=${PROMETHEUS_URL}
K6_RUNS=${K6_RUNS}
K6_VUS=${K6_VUS}
K6_DURATION=${K6_DURATION}
K6_REQUIRE_OPTIONAL_PATHS=true
PROMETHEUS_CAPTURE=best-effort
ORG_ID=${ORG_ID}
WEBHOOK_INVOICE_ID=${WEBHOOK_INVOICE_ID}
WEBHOOK_AMOUNT_MINOR=${WEBHOOK_AMOUNT_MINOR}
INVOICE_PERIOD=${INVOICE_PERIOD}

Secret values intentionally omitted:
API_KEY, JWT_TOKEN, WEBHOOK_SECRET

Tool versions:
$(k6 version)
$(node --version)
EOF

for run in $(seq 1 "$K6_RUNS"); do
  run_dir="${OUT_DIR}/run-${run}"
  mkdir -p "$run_dir"

  echo "== full mixed evidence run ${run}/${K6_RUNS} =="
  capture_prometheus "${run_dir}/prometheus-before.prom"

  BASE_URL="$BASE_URL" \
    API_KEY="$API_KEY" \
    JWT_TOKEN="$JWT_TOKEN" \
    ORG_ID="$ORG_ID" \
    WEBHOOK_INVOICE_ID="$WEBHOOK_INVOICE_ID" \
    WEBHOOK_SECRET="$WEBHOOK_SECRET" \
    WEBHOOK_AMOUNT_MINOR="$WEBHOOK_AMOUNT_MINOR" \
    INVOICE_PERIOD="$INVOICE_PERIOD" \
    K6_REQUIRE_OPTIONAL_PATHS=true \
    K6_VUS="$K6_VUS" \
    K6_DURATION="$K6_DURATION" \
    k6 run \
      --summary-export "${run_dir}/summary.json" \
      k6/mixed-usage-test.js \
      >"${run_dir}/console.txt" 2>&1
  strip_trailing_whitespace "${run_dir}/console.txt"

  capture_prometheus "${run_dir}/prometheus-after.prom"

  node scripts/validate-k6-full-mixed-summary.mjs "${run_dir}/summary.json"
done

node scripts/summarize-full-mixed-evidence.mjs "$OUT_DIR"

cat >"${OUT_DIR}/README.md" <<EOF
# Full Mixed Evidence Capture

This directory was produced by \`scripts/run-full-mixed-evidence.sh\`.

It captures repeated local full mixed smoke artifacts for:

- k6 summary and console output
- Prometheus samples before and after each run when the endpoint is accessible
- sanitized command/environment metadata
- \`capture-summary.json\` scenario-validation rollup

This artifact is evidence capture only. It does not automatically promote
throughput, latency, or error-rate numbers to a public benchmark claim. Update
\`docs/PERF_RESULT.md\` only after reviewing repeatability, environment, dataset,
and run-to-run variance.
EOF

echo "Full mixed evidence captured under: ${OUT_DIR}"
