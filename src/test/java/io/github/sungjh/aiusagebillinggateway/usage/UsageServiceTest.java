package io.github.sungjh.aiusagebillinggateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sungjh.aiusagebillinggateway.domain.UsageEvent;
import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.quota.QuotaService;
import io.github.sungjh.aiusagebillinggateway.repository.UsageEventRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UsageServiceTest {

    @Test
    void requestHashIncludesOccurredAt() throws Exception {
        UsageService usageService = new UsageService(
                mock(UsageEventRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                mock(MetricsService.class),
                mock(QuotaService.class),
                Clock.systemUTC());
        UsageEventRequest first = new UsageEventRequest(
                UsageMetric.REQUEST,
                1,
                Instant.parse("2026-05-01T00:00:00Z"),
                null);
        UsageEventRequest second = new UsageEventRequest(
                UsageMetric.REQUEST,
                1,
                Instant.parse("2026-05-02T00:00:00Z"),
                null);

        assertThat(requestHash(usageService, first))
                .isNotEqualTo(requestHash(usageService, second));
    }

    @Test
    void duplicateOrConflictRecordsIdempotencyConflictMetric() throws Exception {
        MetricsService metricsService = mock(MetricsService.class);
        UsageService usageService = new UsageService(
                mock(UsageEventRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                metricsService,
                mock(QuotaService.class),
                Clock.systemUTC());
        UsageEvent existing = new UsageEvent(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "idem-1",
                "first-hash",
                UsageMetric.REQUEST,
                1,
                Instant.parse("2026-05-01T00:00:00Z"),
                "{}");

        assertThatThrownBy(() -> duplicateOrConflict(usageService, existing, "second-hash"))
                .isInstanceOf(ResponseStatusException.class);

        verify(metricsService).idempotencyConflict();
    }

    private String requestHash(UsageService usageService, UsageEventRequest request) throws Exception {
        Method method = UsageService.class.getDeclaredMethod("requestHash", UsageEventRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(usageService, request);
    }

    private void duplicateOrConflict(
            UsageService usageService,
            UsageEvent existing,
            String requestHash) throws Exception {
        Method method = UsageService.class.getDeclaredMethod("duplicateOrConflict", UsageEvent.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(usageService, existing, requestHash);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }
}
