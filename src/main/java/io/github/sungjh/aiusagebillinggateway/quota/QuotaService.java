package io.github.sungjh.aiusagebillinggateway.quota;

import io.github.sungjh.aiusagebillinggateway.domain.Plan;
import io.github.sungjh.aiusagebillinggateway.domain.Subscription;
import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.PlanRepository;
import io.github.sungjh.aiusagebillinggateway.repository.QuotaCounterRepository;
import io.github.sungjh.aiusagebillinggateway.repository.SubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuotaService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final QuotaCounterRepository quotaCounterRepository;
    private final StringRedisTemplate redisTemplate;
    private final MetricsService metricsService;
    private final long rateLimitPerMinute;

    public QuotaService(
            SubscriptionRepository subscriptionRepository,
            PlanRepository planRepository,
            QuotaCounterRepository quotaCounterRepository,
            StringRedisTemplate redisTemplate,
            MetricsService metricsService,
            @Value("${gateway.rate-limit-per-minute}") long rateLimitPerMinute) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.quotaCounterRepository = quotaCounterRepository;
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMonthlyQuotaForGatewayUsage(UUID organizationId, long quantity) {
        reserveMonthlyQuotaForGatewayUsage(organizationId, quantity, Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMonthlyQuotaForGatewayUsage(UUID organizationId, long quantity, Instant occurredAt) {
        reserveMonthlyQuota(
                organizationId,
                UsageMetric.REQUEST,
                occurredAt,
                quantity);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMonthlyQuota(
            UUID organizationId,
            UsageMetric metric,
            Instant occurredAt,
            long quantity) {
        Subscription subscription = subscriptionRepository.findByOrganizationIdForUpdate(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Subscription missing"));
        Plan plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Plan missing"));
        if (plan.isOverageAllowed()) {
            return;
        }
        YearMonth period = YearMonth.from(occurredAt.atZone(ZoneOffset.UTC));
        LocalDate periodStart = period.atDay(1);
        quotaCounterRepository.ensureCounter(organizationId, metric, periodStart);
        int reserved = quotaCounterRepository.reserve(
                organizationId,
                metric,
                periodStart,
                quantity,
                plan.getIncludedQuantity());
        if (reserved != 1) {
            metricsService.quotaExceeded();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Monthly quota exceeded");
        }
    }

    public void checkRateLimit(UUID apiKeyId) {
        try {
            long window = java.time.Instant.now().getEpochSecond() / 60;
            String key = "rate:api-key:" + apiKeyId + ":" + window;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(2));
            }
            if (count != null && count > rateLimitPerMinute) {
                metricsService.gatewayRateLimited();
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
            }
        } catch (RedisConnectionFailureException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable");
        }
    }
}
