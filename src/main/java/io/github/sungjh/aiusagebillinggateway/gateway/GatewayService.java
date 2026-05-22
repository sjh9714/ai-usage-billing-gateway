package io.github.sungjh.aiusagebillinggateway.gateway;

import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.quota.QuotaService;
import io.github.sungjh.aiusagebillinggateway.security.AuthenticatedApiKey;
import io.github.sungjh.aiusagebillinggateway.usage.UsageEventResponse;
import io.github.sungjh.aiusagebillinggateway.usage.UsageService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GatewayService {

    private final QuotaService quotaService;
    private final UsageService usageService;
    private final MetricsService metricsService;
    private final Clock clock;

    public GatewayService(
            QuotaService quotaService,
            UsageService usageService,
            MetricsService metricsService,
            Clock clock) {
        this.quotaService = quotaService;
        this.usageService = usageService;
        this.metricsService = metricsService;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> mockCompletion(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            String prompt) {
        metricsService.gatewayRequest();
        Optional<UsageEventResponse> replay =
                usageService.findGatewayReplay(apiKey, idempotencyKey, prompt);
        if (replay.isPresent()) {
            return mockResponse(true);
        }
        Instant occurredAt = Instant.now(clock);
        UsageEventResponse usage =
                usageService.recordGatewayUsage(apiKey, idempotencyKey, prompt, occurredAt);
        if (!usage.duplicate()) {
            quotaService.checkRateLimit(apiKey.apiKeyId());
        }
        return mockResponse(usage.duplicate());
    }

    private Map<String, Object> mockResponse(boolean duplicate) {
        return Map.of(
                "provider", "mock",
                "model", "mock-completion-v1",
                "completion", "Mock response for portfolio verification",
                "duplicate", duplicate);
    }
}
