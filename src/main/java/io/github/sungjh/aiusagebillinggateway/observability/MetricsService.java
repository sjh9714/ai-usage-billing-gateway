package io.github.sungjh.aiusagebillinggateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricsService {

    private final Counter usageEventsIngested;
    private final Counter duplicateUsageEvents;
    private final Counter quotaExceeded;
    private final Counter apiKeyAuthFailures;
    private final Counter invoicesGenerated;
    private final Counter invoicesFailed;
    private final Counter paymentWebhooksReceived;
    private final Counter paymentWebhookDuplicates;
    private final Counter paymentWebhookConflicts;
    private final Counter ledgerEntriesCreated;
    private final Counter ledgerGroupsCreated;
    private final Counter auditLogsCreated;
    private final Counter gatewayRequests;
    private final Counter gatewayRateLimited;
    private final Counter idempotencyConflicts;

    public MetricsService(MeterRegistry meterRegistry) {
        this.usageEventsIngested = meterRegistry.counter("usage.events.ingested");
        this.duplicateUsageEvents = meterRegistry.counter("usage.events.duplicates");
        this.quotaExceeded = meterRegistry.counter("quota.exceeded");
        this.apiKeyAuthFailures = meterRegistry.counter("api.key.auth.failures");
        this.invoicesGenerated = meterRegistry.counter("invoices.generated");
        this.invoicesFailed = meterRegistry.counter("invoices.failed");
        this.paymentWebhooksReceived = meterRegistry.counter("payment.webhooks.received");
        this.paymentWebhookDuplicates = meterRegistry.counter("payment.webhooks.duplicates");
        this.paymentWebhookConflicts = meterRegistry.counter("payment.webhooks.conflicts");
        this.ledgerEntriesCreated = meterRegistry.counter("ledger.entries.created");
        this.ledgerGroupsCreated = meterRegistry.counter("ledger.groups.created");
        this.auditLogsCreated = meterRegistry.counter("audit.logs.created");
        this.gatewayRequests = meterRegistry.counter("gateway.requests");
        this.gatewayRateLimited = meterRegistry.counter("gateway.rate_limited");
        this.idempotencyConflicts = meterRegistry.counter("idempotency.conflicts");
    }

    public void usageIngested() {
        usageEventsIngested.increment();
    }

    public void duplicateUsage() {
        duplicateUsageEvents.increment();
    }

    public void quotaExceeded() {
        quotaExceeded.increment();
    }

    public void apiKeyAuthFailure() {
        apiKeyAuthFailures.increment();
    }

    public void invoiceGenerated() {
        invoicesGenerated.increment();
    }

    public void invoiceFailed() {
        invoicesFailed.increment();
    }

    public void webhookReceived() {
        paymentWebhooksReceived.increment();
    }

    public void webhookDuplicate() {
        paymentWebhookDuplicates.increment();
    }

    public void webhookConflict() {
        paymentWebhookConflicts.increment();
    }

    public void ledgerEntryCreated() {
        ledgerEntriesCreated.increment();
    }

    public void ledgerGroupCreated() {
        ledgerGroupsCreated.increment();
    }

    public void auditLogCreated() {
        auditLogsCreated.increment();
    }

    public void gatewayRequest() {
        gatewayRequests.increment();
    }

    public void gatewayRateLimited() {
        gatewayRateLimited.increment();
    }

    public void idempotencyConflict() {
        idempotencyConflicts.increment();
    }
}
