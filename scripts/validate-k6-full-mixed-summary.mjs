#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export function metricNumber(metrics, name, keys) {
  const metric = metrics[name];

  if (!metric) {
    return undefined;
  }

  for (const key of keys) {
    const value = metric.values?.[key] ?? metric[key];
    if (typeof value === "number") {
      return value;
    }
  }

  return undefined;
}

export function readK6Summary(summaryPath) {
  return JSON.parse(readFileSync(summaryPath, "utf8"));
}

export function validateFullMixedSummary(summary) {
  const metrics = summary.metrics ?? {};
  const evidence = {
    checksRate: metricNumber(metrics, "checks", ["rate", "value"]),
    httpFailedRate: metricNumber(metrics, "http_req_failed", [
      "rate",
      "value",
    ]),
    invoicePathCount: metricNumber(metrics, "invoice_path_count", ["count"]),
    webhookPathCount: metricNumber(metrics, "webhook_path_count", ["count"]),
    skippedOptionalPathCount: metricNumber(
      metrics,
      "skipped_optional_path_count",
      ["count"],
    ),
  };
  const failures = [];

  if (evidence.checksRate !== 1) {
    failures.push(`checks rate expected 1, got ${evidence.checksRate}`);
  }
  if (evidence.httpFailedRate !== 0) {
    failures.push(
      `http_req_failed rate expected 0, got ${evidence.httpFailedRate}`,
    );
  }
  if (!evidence.invoicePathCount || evidence.invoicePathCount <= 0) {
    failures.push(
      `invoice_path_count expected > 0, got ${evidence.invoicePathCount}`,
    );
  }
  if (!evidence.webhookPathCount || evidence.webhookPathCount <= 0) {
    failures.push(
      `webhook_path_count expected > 0, got ${evidence.webhookPathCount}`,
    );
  }
  if (evidence.skippedOptionalPathCount !== 0) {
    failures.push(
      `skipped_optional_path_count expected 0, got ${evidence.skippedOptionalPathCount}`,
    );
  }

  return {
    valid: failures.length === 0,
    failures,
    evidence,
  };
}

export function validateSummaryFile(summaryPath) {
  return validateFullMixedSummary(readK6Summary(summaryPath));
}

function runCli() {
  const summaryPath = process.argv[2];

  if (!summaryPath) {
    console.error(
      "Usage: node scripts/validate-k6-full-mixed-summary.mjs <summary.json>",
    );
    process.exit(1);
  }

  const result = validateSummaryFile(summaryPath);

  if (!result.valid) {
    console.error(`Invalid full mixed evidence summary: ${summaryPath}`);
    for (const failure of result.failures) {
      console.error(`- ${failure}`);
    }
    process.exit(1);
  }

  console.log(`Valid full mixed evidence summary: ${summaryPath}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runCli();
}
