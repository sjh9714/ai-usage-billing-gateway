import http from "k6/http";
import crypto from "k6/crypto";
import { Counter } from "k6/metrics";
import { check, sleep } from "k6";

// Local mixed smoke. The default path checks gateway + direct usage ingestion.
// Invoice and webhook branches are included only when their env vars are provided.
// Set K6_REQUIRE_OPTIONAL_PATHS=true for a full mixed run that must execute
// invoice and webhook branches instead of silently counting them as skipped.
// In full optional mode, WEBHOOK_AMOUNT_MINOR must be passed explicitly and
// must match the selected invoice total.
// This script still does not publish benchmark results; PERF_RESULT remains the source
// of truth for what has actually been measured.
const vus = Number(__ENV.K6_VUS || 1);
const duration = __ENV.K6_DURATION || "30s";
const requireOptionalPaths =
  (__ENV.K6_REQUIRE_OPTIONAL_PATHS || "").toLowerCase() === "true";
const thresholds = {
  http_req_failed: ["rate<0.05"],
  gateway_path_count: ["count>0"],
  usage_path_count: ["count>0"],
};

if (requireOptionalPaths) {
  thresholds.http_req_failed = ["rate==0"];
  thresholds.checks = ["rate==1"];
  thresholds.invoice_path_count = ["count>0"];
  thresholds.webhook_path_count = ["count>0"];
  thresholds.skipped_optional_path_count = ["count==0"];
}

export const options = {
  vus,
  duration,
  thresholds,
};

const baseUrl = __ENV.BASE_URL || "http://localhost:8080";
const apiKey = __ENV.API_KEY || "replace-with-created-api-key";
const jwtToken = __ENV.JWT_TOKEN || "";
const organizationId = __ENV.ORG_ID || "";
const invoicePeriod = __ENV.INVOICE_PERIOD || new Date().toISOString().slice(0, 7);
const webhookInvoiceId = __ENV.WEBHOOK_INVOICE_ID || "";
const webhookSecret = __ENV.WEBHOOK_SECRET || "";
const webhookAmountMinor = Number(__ENV.WEBHOOK_AMOUNT_MINOR || 100);
const webhookProviderEventId =
  __ENV.WEBHOOK_PROVIDER_EVENT_ID ||
  (webhookInvoiceId ? `k6-payment-${webhookInvoiceId}` : "");
const skippedOptionalPath = new Counter("skipped_optional_path_count");
const gatewayPath = new Counter("gateway_path_count");
const usagePath = new Counter("usage_path_count");
const invoicePath = new Counter("invoice_path_count");
const webhookPath = new Counter("webhook_path_count");

export default function () {
  if (apiKey === "replace-with-created-api-key") {
    throw new Error(
      "Set API_KEY to a raw API key created through the local app before running k6.",
    );
  }
  if (requireOptionalPaths) {
    const missing = [
      ["JWT_TOKEN", jwtToken],
      ["ORG_ID", organizationId],
      ["WEBHOOK_INVOICE_ID", webhookInvoiceId],
      ["WEBHOOK_SECRET", webhookSecret],
      ["WEBHOOK_AMOUNT_MINOR", __ENV.WEBHOOK_AMOUNT_MINOR],
    ]
      .filter(([, value]) => !value)
      .map(([name]) => name);

    if (missing.length > 0) {
      throw new Error(
        `K6_REQUIRE_OPTIONAL_PATHS=true requires: ${missing.join(", ")}`,
      );
    }
  }

  const bucket = (__ITER + (__VU - 1) * 7) % 20;

  if (bucket < 14) {
    postGatewayCompletion();
  } else if (bucket < 18) {
    postUsageEvent();
  } else if (bucket === 18) {
    postInvoiceGeneration();
  } else {
    postPaymentWebhook();
  }

  sleep(1);
}

function postGatewayCompletion() {
  gatewayPath.add(1);

  const gatewayResponse = http.post(
    `${baseUrl}/v1/gateway/mock-completion`,
    JSON.stringify({ prompt: "portfolio load probe" }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": apiKey,
        "Idempotency-Key": `gateway-k6-${__VU}-${__ITER}`,
      },
    },
  );

  check(gatewayResponse, {
    "gateway accepted request": (response) =>
      response.status >= 200 && response.status < 300,
  });
}

function postUsageEvent() {
  usagePath.add(1);

  const usageResponse = http.post(
    `${baseUrl}/api/usage/events`,
    JSON.stringify({
      metric: "REQUEST",
      quantity: 1,
      metadata: { source: "k6" },
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": apiKey,
        "Idempotency-Key": `k6-${__VU}-${__ITER}`,
      },
    },
  );

  check(usageResponse, {
    "usage accepted request": (response) =>
      response.status >= 200 && response.status < 300,
  });
}

function postInvoiceGeneration() {
  if (!jwtToken || !organizationId) {
    skippedOptionalPath.add(1);
    return;
  }

  invoicePath.add(1);

  const invoiceResponse = http.post(
    `${baseUrl}/api/organizations/${organizationId}/invoices/generate?period=${invoicePeriod}`,
    null,
    {
      headers: {
        Authorization: `Bearer ${jwtToken}`,
      },
    },
  );

  check(invoiceResponse, {
    "invoice generation accepted or replayed": (response) =>
      response.status === 201 || response.status === 200,
  });
}

function postPaymentWebhook() {
  if (!webhookInvoiceId || !webhookSecret) {
    skippedOptionalPath.add(1);
    return;
  }

  webhookPath.add(1);

  const body = JSON.stringify({
    providerEventId: webhookProviderEventId,
    type: "payment.succeeded",
    invoiceId: webhookInvoiceId,
    amountMinor: webhookAmountMinor,
    currency: "USD",
  });
  const signature = crypto.hmac("sha256", webhookSecret, body, "hex");

  const webhookResponse = http.post(
    `${baseUrl}/api/webhooks/payments`,
    body,
    {
      headers: {
        "Content-Type": "application/json",
        "X-Webhook-Signature": signature,
      },
    },
  );

  check(webhookResponse, {
    "payment webhook processed": (response) =>
      response.status >= 200 && response.status < 300,
  });
}
