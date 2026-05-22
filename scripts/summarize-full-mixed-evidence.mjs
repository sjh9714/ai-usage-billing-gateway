#!/usr/bin/env node

import {
  existsSync,
  readdirSync,
  readFileSync,
  renameSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { basename, join, relative } from "node:path";
import { pathToFileURL } from "node:url";

import {
  readK6Summary,
  validateFullMixedSummary,
} from "./validate-k6-full-mixed-summary.mjs";

function listRunDirs(outDir) {
  return readdirSync(outDir)
    .filter((name) => /^run-[1-9][0-9]*$/.test(name))
    .map((name) => join(outDir, name))
    .filter((path) => statSync(path).isDirectory())
    .sort((left, right) => {
      const leftNumber = Number(basename(left).replace("run-", ""));
      const rightNumber = Number(basename(right).replace("run-", ""));

      return leftNumber - rightNumber;
    });
}

function readMetadata(outDir) {
  const metadataPath = join(outDir, "metadata.txt");

  if (!existsSync(metadataPath)) {
    return undefined;
  }

  return {
    path: relative(outDir, metadataPath),
    text: readFileSync(metadataPath, "utf8"),
  };
}

export function buildCaptureSummary(outDir) {
  if (!existsSync(outDir) || !statSync(outDir).isDirectory()) {
    throw new Error(`Evidence directory does not exist: ${outDir}`);
  }

  const runDirs = listRunDirs(outDir);

  if (runDirs.length === 0) {
    throw new Error(`No run-* directories found under: ${outDir}`);
  }

  const failures = [];
  const runs = runDirs.map((runDir) => {
    const summaryPath = join(runDir, "summary.json");
    const runName = basename(runDir);

    if (!existsSync(summaryPath)) {
      failures.push(`${runName}/summary.json is missing`);
      return {
        run: runName,
        summaryPath: relative(outDir, summaryPath),
        valid: false,
        failures: ["summary.json is missing"],
      };
    }

    const validation = validateFullMixedSummary(readK6Summary(summaryPath));

    if (!validation.valid) {
      for (const failure of validation.failures) {
        failures.push(`${runName}: ${failure}`);
      }
    }

    return {
      run: runName,
      summaryPath: relative(outDir, summaryPath),
      valid: validation.valid,
      failures: validation.failures,
      ...validation.evidence,
    };
  });

  if (failures.length > 0) {
    throw new Error(
      [
        `Invalid full mixed evidence capture: ${outDir}`,
        ...failures.map((failure) => `- ${failure}`),
      ].join("\n"),
    );
  }

  const metadata = readMetadata(outDir);

  return {
    claimStatus: "scenario-validation-not-benchmark",
    interpretation:
      "Repeated full mixed smoke capture metadata. This rollup checks scenario guard conditions only and does not publish throughput, latency, or error-rate benchmark aggregates.",
    runCount: runs.length,
    metadataPath: metadata?.path,
    runs,
  };
}

export function writeCaptureSummary(outDir) {
  const captureSummary = buildCaptureSummary(outDir);
  const outputPath = join(outDir, "capture-summary.json");
  const tempPath = `${outputPath}.tmp`;

  writeFileSync(tempPath, `${JSON.stringify(captureSummary, null, 2)}\n`);
  renameSync(tempPath, outputPath);

  return {
    outputPath,
    captureSummary,
  };
}

function runCli() {
  const outDir = process.argv[2];

  if (!outDir) {
    console.error(
      "Usage: node scripts/summarize-full-mixed-evidence.mjs <full-mixed-output-dir>",
    );
    process.exit(1);
  }

  try {
    const { outputPath, captureSummary } = writeCaptureSummary(outDir);
    console.log(
      `Wrote full mixed capture summary: ${outputPath} (${captureSummary.runCount} runs)`,
    );
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runCli();
}
