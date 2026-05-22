package io.github.sungjh.aiusagebillinggateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.PlanRepository;
import io.github.sungjh.aiusagebillinggateway.repository.QuotaCounterRepository;
import io.github.sungjh.aiusagebillinggateway.repository.SubscriptionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class QuotaServiceTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final QuotaCounterRepository quotaCounterRepository = mock(QuotaCounterRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MetricsService metricsService = mock(MetricsService.class);
    private final QuotaService quotaService = new QuotaService(
            subscriptionRepository,
            planRepository,
            quotaCounterRepository,
            redisTemplate,
            metricsService,
            10);

    @Test
    void checkRateLimitFailsClosedWhenRedisIsUnavailable() {
        ValueOperations<String, String> operations = mock();
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> quotaService.checkRateLimit(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void checkRateLimitRecordsRateLimitedOutcomeBeforeRejecting() {
        ValueOperations<String, String> operations = mock();
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.increment(anyString())).thenReturn(11L);

        assertThatThrownBy(() -> quotaService.checkRateLimit(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(metricsService).gatewayRateLimited();
    }
}
