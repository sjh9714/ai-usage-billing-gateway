package io.github.sungjh.aiusagebillinggateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sungjh.aiusagebillinggateway.common.Hashing;
import io.github.sungjh.aiusagebillinggateway.domain.UsageEvent;
import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.quota.QuotaService;
import io.github.sungjh.aiusagebillinggateway.repository.UsageEventRepository;
import io.github.sungjh.aiusagebillinggateway.security.AuthenticatedApiKey;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UsageService {

    private final UsageEventRepository usageEventRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;
    private final QuotaService quotaService;
    private final Clock clock;

    public UsageService(
            UsageEventRepository usageEventRepository,
            ObjectMapper objectMapper,
            MetricsService metricsService,
            QuotaService quotaService,
            Clock clock) {
        this.usageEventRepository = usageEventRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.quotaService = quotaService;
        this.clock = clock;
    }

    @Transactional
    public UsageEventResponse ingest(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            UsageEventRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usage quantity must be positive");
        }
        String requestHash = requestHash(request);
        return usageEventRepository
                .findByOrganizationIdAndIdempotencyKey(apiKey.organizationId(), idempotencyKey)
                .map(existing -> duplicateOrConflict(existing, requestHash))
                .orElseGet(() -> {
                    Instant occurredAt = occurredAt(request);
                    return create(apiKey, idempotencyKey, requestHash, request, occurredAt);
                });
    }

    @Transactional
    public Optional<UsageEventResponse> findGatewayReplay(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            String prompt) {
        String scopedKey = gatewayIdempotencyKey(idempotencyKey);
        String requestHash = requestHash(gatewayUsageRequest(prompt));
        return usageEventRepository
                .findByOrganizationIdAndIdempotencyKey(apiKey.organizationId(), scopedKey)
                .map(existing -> duplicateOrConflict(existing, requestHash));
    }

    @Transactional
    public UsageEventResponse recordGatewayUsage(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            String prompt) {
        return recordGatewayUsage(apiKey, idempotencyKey, prompt, Instant.now(clock));
    }

    @Transactional
    public UsageEventResponse recordGatewayUsage(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            String prompt,
            Instant occurredAt) {
        UsageEventRequest request = gatewayUsageRequest(prompt);
        return create(
                apiKey,
                gatewayIdempotencyKey(idempotencyKey),
                requestHash(request),
                request,
                occurredAt);
    }

    private UsageEventRequest gatewayUsageRequest(String prompt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gateway");
        metadata.put("promptHash", Hashing.sha256Hex(prompt));
        UsageEventRequest request = new UsageEventRequest(
                UsageMetric.REQUEST,
                1,
                null,
                objectMapper.valueToTree(metadata));
        return request;
    }

    private String gatewayIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        return "gateway:" + idempotencyKey;
    }

    private UsageEventResponse duplicateOrConflict(UsageEvent existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            metricsService.idempotencyConflict();
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different payload");
        }
        metricsService.duplicateUsage();
        return new UsageEventResponse(existing.getId(), true);
    }

    private UsageEventResponse create(
            AuthenticatedApiKey apiKey,
            String idempotencyKey,
            String requestHash,
            UsageEventRequest request,
            Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        int inserted = usageEventRepository.insertIfAbsent(
                eventId,
                apiKey.organizationId(),
                apiKey.apiKeyId(),
                idempotencyKey,
                requestHash,
                request.metric().name(),
                request.quantity(),
                occurredAt,
                metadataString(request.metadata()));
        if (inserted == 0) {
            return usageEventRepository
                    .findByOrganizationIdAndIdempotencyKey(apiKey.organizationId(), idempotencyKey)
                    .map(existing -> duplicateOrConflict(existing, requestHash))
                    .orElseThrow(() -> new IllegalStateException("Usage idempotency conflict could not be resolved"));
        }
        quotaService.reserveMonthlyQuota(
                apiKey.organizationId(),
                request.metric(),
                occurredAt,
                request.quantity());
        metricsService.usageIngested();
        return new UsageEventResponse(eventId, false);
    }

    private Instant occurredAt(UsageEventRequest request) {
        return request.occurredAt() == null ? Instant.now(clock) : request.occurredAt();
    }

    private String requestHash(UsageEventRequest request) {
        try {
            Map<String, Object> hashInput = new LinkedHashMap<>();
            hashInput.put("metric", request.metric());
            hashInput.put("quantity", request.quantity());
            hashInput.put("occurredAt", request.occurredAt());
            hashInput.put("metadata", metadataString(request.metadata()));
            return Hashing.sha256Hex(objectMapper.writeValueAsString(hashInput));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash usage event request", exception);
        }
    }

    private String metadataString(JsonNode metadata) {
        try {
            return metadata == null || metadata.isNull() ? "{}" : objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata", exception);
        }
    }
}
