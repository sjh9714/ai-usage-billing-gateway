package io.github.sungjh.aiusagebillinggateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MetricsServiceTest {

    @Test
    void registersLowCardinalityOutcomeCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metricsService = new MetricsService(registry);

        metricsService.gatewayRequest();
        metricsService.gatewayRateLimited();
        metricsService.idempotencyConflict();
        metricsService.webhookConflict();
        metricsService.ledgerGroupCreated();

        assertThat(registry.counter("gateway.requests").count()).isEqualTo(1);
        assertThat(registry.counter("gateway.rate_limited").count()).isEqualTo(1);
        assertThat(registry.counter("idempotency.conflicts").count()).isEqualTo(1);
        assertThat(registry.counter("payment.webhooks.conflicts").count()).isEqualTo(1);
        assertThat(registry.counter("ledger.groups.created").count()).isEqualTo(1);
    }
}
