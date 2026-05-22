#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import { buildCaptureSummary } from "./summarize-full-mixed-evidence.mjs";
import {
  readK6Summary,
  validateSummaryFile,
} from "./validate-k6-full-mixed-summary.mjs";

const goodSummaryPath =
  "docs/evidence/k6/mixed-usage-full-20260522081328-summary.json";
const badSummaryPath =
  "docs/evidence/k6/mixed-usage-full-20260522080203-summary.json";

function tempEvidenceDir() {
  return mkdtempSync(join(tmpdir(), "ai-billing-k6-evidence-"));
}

function copySummary(outDir, run, sourcePath) {
  const runDir = join(outDir, `run-${run}`);

  mkdirSync(runDir, { recursive: true });
  cpSync(sourcePath, join(runDir, "summary.json"));
}

describe("full mixed evidence tools", () => {
  it("accepts the current full mixed smoke summary", () => {
    const result = validateSummaryFile(goodSummaryPath);

    assert.equal(result.valid, true);
    assert.equal(result.evidence.checksRate, 1);
    assert.equal(result.evidence.httpFailedRate, 0);
    assert.equal(result.evidence.skippedOptionalPathCount, 0);
    assert.ok(result.evidence.invoicePathCount > 0);
    assert.ok(result.evidence.webhookPathCount > 0);
  });

  it("rejects the superseded diagnostic summary", () => {
    const result = validateSummaryFile(badSummaryPath);

    assert.equal(result.valid, false);
    assert.match(result.failures.join("\n"), /checks rate expected 1/);
  });

  it("rolls up repeated full mixed captures without benchmark aggregates", () => {
    const outDir = tempEvidenceDir();

    try {
      copySummary(outDir, 1, goodSummaryPath);
      copySummary(outDir, 2, goodSummaryPath);
      writeFileSync(join(outDir, "metadata.txt"), "sanitized metadata\n");

      const captureSummary = buildCaptureSummary(outDir);

      assert.equal(
        captureSummary.claimStatus,
        "scenario-validation-not-benchmark",
      );
      assert.equal(captureSummary.runCount, 2);
      assert.equal(captureSummary.metadataPath, "metadata.txt");
      assert.deepEqual(
        captureSummary.runs.map((run) => run.valid),
        [true, true],
      );
      for (const run of captureSummary.runs) {
        assert.equal("httpReqDurationP95" in run, false);
        assert.equal("throughput" in run, false);
        assert.equal("iterationsPerSecond" in run, false);
      }
    } finally {
      rmSync(outDir, { recursive: true, force: true });
    }
  });

  it("refuses captures with missing summaries", () => {
    const outDir = tempEvidenceDir();

    try {
      mkdirSync(join(outDir, "run-1"), { recursive: true });

      assert.throws(
        () => buildCaptureSummary(outDir),
        /run-1\/summary\.json is missing/,
      );
    } finally {
      rmSync(outDir, { recursive: true, force: true });
    }
  });

  it("refuses captures with failed per-run summaries", () => {
    const outDir = tempEvidenceDir();

    try {
      copySummary(outDir, 1, goodSummaryPath);
      copySummary(outDir, 2, badSummaryPath);

      assert.throws(
        () => buildCaptureSummary(outDir),
        /run-2: checks rate expected 1/,
      );
    } finally {
      rmSync(outDir, { recursive: true, force: true });
    }
  });

  it("supports direct summary parsing from k6 summary-export artifacts", () => {
    const summary = readK6Summary(goodSummaryPath);
    const raw = JSON.parse(readFileSync(goodSummaryPath, "utf8"));

    assert.deepEqual(summary.metrics.checks, raw.metrics.checks);
  });
});
