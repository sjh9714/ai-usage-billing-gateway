package io.github.sungjh.aiusagebillinggateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.quota.QuotaService;
import io.github.sungjh.aiusagebillinggateway.security.AuthenticatedApiKey;
import io.github.sungjh.aiusagebillinggateway.usage.UsageEventResponse;
import io.github.sungjh.aiusagebillinggateway.usage.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayServiceTest {

    @Test
    void mockCompletionUsesOneCapturedTimestampForUsageRecordAfterRateLimit() {
        QuotaService quotaService = mock(QuotaService.class);
        UsageService usageService = mock(UsageService.class);
        MetricsService metricsService = mock(MetricsService.class);
        Instant fixedNow = Instant.parse("2026-05-31T23:59:59.999Z");
        GatewayService gatewayService = new GatewayService(
                quotaService,
                usageService,
                metricsService,
                Clock.fixed(fixedNow, ZoneOffset.UTC));
        AuthenticatedApiKey apiKey = new AuthenticatedApiKey(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ak_test");
        when(usageService.findGatewayReplay(apiKey, "retry-1", "hello"))
                .thenReturn(Optional.empty());
        when(usageService.recordGatewayUsage(apiKey, "retry-1", "hello", fixedNow))
                .thenReturn(new UsageEventResponse(UUID.randomUUID(), false));

        Map<String, Object> response = gatewayService.mockCompletion(apiKey, "retry-1", "hello");

        assertThat(response).containsEntry("duplicate", false);
        verify(metricsService).gatewayRequest();
        verify(quotaService).checkRateLimit(apiKey.apiKeyId());
        verify(usageService).recordGatewayUsage(apiKey, "retry-1", "hello", fixedNow);
    }
}
